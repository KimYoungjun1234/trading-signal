package org.example.member.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class MemberRequest {

    private String name;
    private String email;

    public MemberRequest(String name, String email) {
        this.name = name;
        this.email = email;
    }
}
