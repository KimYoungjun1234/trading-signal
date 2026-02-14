package org.example.member.repository;

import org.example.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemberRepository extends JpaRepository<Member, Long> {

    List<Member> findByName(String name);

    List<Member> findByEmail(String email);
}
