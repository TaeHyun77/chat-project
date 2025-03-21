package com.example.chat.oauth;

import java.util.Map;

public class KakaoResponse implements OAuth2Response {

    private final Map<String, Object> attribute;

    public KakaoResponse(Map<String, Object> attribute) {

        this.attribute = attribute;
    }

    @Override
    public String getProvider() {

        return "kakao";
    }

    @Override
    public String getProviderId() {
        return attribute.get("id").toString();
    }

    @Override
    public String getEmail() {
        return (String) ((Map) attribute.get("kakao_account")).get("email");
    }

    @Override
    public String getName() {
        return (String) ((Map) attribute.get("properties")).get("nickname");
    }
}
