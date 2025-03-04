package com.example.chat.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    UNKNOWN("000_UNKNOWN", "알 수 없는 에러가 발생했습니다."),

    FAIL_TO_REGISTER_MEMBER("FAIL_TO_REGISTER_MEMBER", "사용자 등록 실패"),

    NOT_FOUND_MEMBER("NOT_FOUND_MEMBER", "사용자를 찾을 수 없습니다."),

    ACCESSTOKEN_IS_EXPIRED("ACCESSTOKEN_IS_EXPIRED", "access 토큰 만료"),

    REFRESHTOKEN_IS_EXPIRED("REFRESHTOKEN_IS_EXPIRED", "refresh 토큰 만료"),

    IS_NOT_REFRESHTOKEN("IS_NOT_REFRESHTOKEN", "refresh 토큰이 아닙니다."),

    INVALID_MESSAGE_TYPE("INVALID_MESSAGE_TYPE", "지원하지 않는 메세지 타입입니다."),

    NOT_FOUND_CHATROOM("NOT_FOUND_CHATROOM", "채팅방을 찾을 수 없습니다.");
    private final String errorCode;

    private final String message;
}
