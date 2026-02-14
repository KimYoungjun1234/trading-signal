package org.example.member.controller;

import lombok.RequiredArgsConstructor;
import org.example.member.dto.MemberRequest;
import org.example.member.entity.Member;
import org.example.member.repository.MemberRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/members")
public class MemberPageController {

    private final MemberRepository memberRepository;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("members", memberRepository.findAll());
        return "member/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("member", new MemberRequest());
        return "member/form";
    }

    @PostMapping("/new")
    public String create(@ModelAttribute MemberRequest request) {
        Member member = new Member(request.getName(), request.getEmail());
        memberRepository.save(member);
        return "redirect:/members";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Member member = memberRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));
        model.addAttribute("member", member);
        return "member/edit";
    }

    @PostMapping("/{id}/edit")
    public String edit(@PathVariable Long id, @ModelAttribute MemberRequest request) {
        Member member = memberRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));
        member.setName(request.getName());
        member.setEmail(request.getEmail());
        memberRepository.save(member);
        return "redirect:/members";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id) {
        memberRepository.deleteById(id);
        return "redirect:/members";
    }
}
