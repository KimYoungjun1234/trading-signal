package org.example.member.dto;

import lombok.Getter;
import org.example.member.entity.Member;

@Getter
public class MemberResponse {

    private final Long id;
    private final String name;
    private final String email;

    public MemberResponse(Member member) {
        this.id = member.getId();
        this.name = member.getName();
        this.email = member.getEmail();
    }
}
