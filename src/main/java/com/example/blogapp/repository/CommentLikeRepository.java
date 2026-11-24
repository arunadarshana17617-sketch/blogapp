package com.example.blogapp.repository;

import com.example.blogapp.model.CommentLike;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CommentLikeRepository extends JpaRepository<CommentLike, Long> {
    List<CommentLike> findByCommentIdIn(Collection<Long> commentIds);
    Optional<CommentLike> findByCommentIdAndUserId(Long commentId, Long userId);
    long countByCommentId(Long commentId);
    void deleteByCommentIdIn(Collection<Long> commentIds);
}