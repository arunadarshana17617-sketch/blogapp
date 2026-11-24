package com.example.blogapp.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** one row per (comment, user) — a user can like a comment only once */
@Entity
@Table(name = "comment_likes",
       uniqueConstraints = @UniqueConstraint(columnNames = {"comment_id", "user_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class CommentLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "comment_id", nullable = false)
    private Long commentId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    private LocalDateTime createdAt = LocalDateTime.now();
}