package com.example.chat.chat;

import lombok.Getter;

@Getter
public class MessageRequestDto {

    private String content;

    private String accessToken;

    private String timestamp;
}
