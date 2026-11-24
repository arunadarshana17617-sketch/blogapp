package com.example.blogapp.repository;

import com.example.blogapp.model.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    List<Comment> findByPostIdOrderByCreatedAtAsc(Long postId);

    // replies under one comment
    List<Comment> findByParentId(Long parentId);

    // used when a post is deleted, so its comments are removed too
    void deleteByPostId(Long postId);

    // used by the admin panel when a comment is deleted (removes its replies)
    void deleteByParentId(Long parentId);
}