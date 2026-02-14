package org.example.member.service;

import lombok.RequiredArgsConstructor;
import org.example.member.dto.MemberRequest;
import org.example.member.dto.MemberResponse;
import org.example.member.entity.Member;
import org.example.member.repository.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {

    private final MemberRepository memberRepository;

    @Transactional
    public MemberResponse createMember(MemberRequest request) {
        Member member = new Member(request.getName(), request.getEmail());
        Member savedMember = memberRepository.save(member);
        return new MemberResponse(savedMember);
    }

    public MemberResponse getMember(Long id) {
        Member member = memberRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다. id=" + id));
        return new MemberResponse(member);
    }

    public List<MemberResponse> getAllMembers() {
        return memberRepository.findAll().stream()
                .map(MemberResponse::new)
                .toList();
    }

    @Transactional
    public MemberResponse updateMember(Long id, MemberRequest request) {
        Member member = memberRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다. id=" + id));

        member.setName(request.getName());
        member.setEmail(request.getEmail());

        return new MemberResponse(member);
    }

    @Transactional
    public void deleteMember(Long id) {
        if (!memberRepository.existsById(id)) {
            throw new IllegalArgumentException("회원을 찾을 수 없습니다. id=" + id);
        }
        memberRepository.deleteById(id);
    }
}
