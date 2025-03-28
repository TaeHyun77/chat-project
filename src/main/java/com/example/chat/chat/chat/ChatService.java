package com.example.chat.chat.chat;

import com.example.chat.chat.chatRoom.ChatRoom;
import com.example.chat.chat.chatRoom.ChatRoomRepository;
import com.example.chat.exception.ChatException;
import com.example.chat.exception.ErrorCode;
import com.example.chat.jwt.JwtUtil;
import com.example.chat.member.Member;
import com.example.chat.member.MemberRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Service
public class ChatService {

    private final JwtUtil jwtUtil;
    private final MemberRepository memberRepository;
    private final ChatRepository chatRepository;
    private final ChatRoomRepository chatRoomRepository;

    private final SimpMessageSendingOperations messagingTemplate;

    public void pushMessage(ChatRequestDto requestDto) {

        String username = validateAndExtractUsername(requestDto.getAccessToken());

        Member member = memberRepository.findByUsername(username)
                .orElseThrow(() -> new ChatException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_FOUND_MEMBER));

        handleMessageByType(requestDto, member);
    }

    public List<Chat> getChatHistory(String roomId) {

        return chatRepository.findByChatRoomId(roomId);

    }

    // 토큰 검증 및 사용자 이름 추출
    private String validateAndExtractUsername(String token) {

        if (token == null || !token.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Invalid token format");
        }

        token = token.substring(7);

        try {
            jwtUtil.isExpired(token);
        } catch (ChatException e) {
            throw new ChatException(HttpStatus.UNAUTHORIZED, ErrorCode.ACCESSTOKEN_IS_EXPIRED);
        }

        try {
            return jwtUtil.getUsername(token);
        } catch (Exception e) {
            throw new ChatException(HttpStatus.BAD_REQUEST, ErrorCode.ERROR_TO_GET_USERNAME);
        }
    }

    // 메시지 타입에 따른 처리 로직 분리
    private void handleMessageByType(ChatRequestDto requestDto, Member member) {
        if (requestDto.getChatType() == ChatType.TALK) {
            handleTalkMessage(requestDto, member);
        } else if (requestDto.getChatType() == ChatType.ENTER || requestDto.getChatType() == ChatType.EXIT) {
            handleEnterAndExitMessage(requestDto, member);
        } else {
            throw new ChatException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_MESSAGE_TYPE);
        }
    }

    // 입장, 퇴장 메시지 처리
    private void handleEnterAndExitMessage(ChatRequestDto requestDto, Member member) {

        ChatRoom chatRoom = chatRoomRepository.findByChatRoomId(requestDto.getRoomId());

        if (chatRoom == null) {
            throw new ChatException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND_CHATROOM);
        }

        if (requestDto.getChatType() == ChatType.ENTER) {
            requestDto.setContent(member.getName() + "님이 입장하였습니다.");
        } else if (requestDto.getChatType() == ChatType.EXIT) {
            requestDto.setContent(member.getName() + "님이 퇴장하였습니다.");
        }

        Chat chat = Chat.builder()
                .content(requestDto.getContent())
                .chatRoom(chatRoom)
                .chatType(requestDto.getChatType())
                .build();

        chatRepository.save(chat);

        messagingTemplate.convertAndSend("/topic/chat/" + requestDto.getRoomId(), requestDto);
    }

    // 일반 채팅 메시지 처리
    private void handleTalkMessage(ChatRequestDto requestDto, Member member) {

        ChatRoom chatRoom = chatRoomRepository.findByChatRoomId(requestDto.getRoomId());

        if (chatRoom == null) {
            throw new ChatException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND_CHATROOM);
        }

        // 채팅 저장
        Chat chat = Chat.builder()
                .content(requestDto.getContent())
                .chatRoom(chatRoom)
                .chatType(requestDto.getChatType())
                .username(member.getUsername())
                .name(member.getName())
                .build();

        chatRepository.save(chat);

        // 클라이언트에서 발송된 메세지 전송
        ChatResponseDto sendMessage = ChatResponseDto.builder()
                .chatType(requestDto.getChatType())
                .content(requestDto.getContent())
                .username(member.getUsername())
                .name(member.getName())
                .email(member.getEmail())
                .createdAt(requestDto.getCreatedAt())
                .roomId(requestDto.getRoomId())
                .build();

        messagingTemplate.convertAndSend("/topic/chat/" + requestDto.getRoomId(), sendMessage);
    }
}