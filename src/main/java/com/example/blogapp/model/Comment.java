package com.example.blogapp.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "comments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long postId;

    // null = top-level comment, otherwise the id of the comment this replies to
    private Long parentId;

    // the signed-in user who wrote it (old comments from before login was required can be null)
    private Long userId;

    @Column(nullable = false)
    private String authorName;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    private LocalDateTime createdAt = LocalDateTime.now();

    // set when the author edits the comment
    private Boolean edited = false;

    private LocalDateTime updatedAt;
}