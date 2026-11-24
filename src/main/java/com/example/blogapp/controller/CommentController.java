package com.example.blogapp.controller;

import com.example.blogapp.model.Comment;
import com.example.blogapp.model.CommentLike;
import com.example.blogapp.model.Post;
import com.example.blogapp.model.User;
import com.example.blogapp.repository.CommentLikeRepository;
import com.example.blogapp.repository.CommentRepository;
import com.example.blogapp.repository.PostRepository;
import com.example.blogapp.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Facebook-style comments.
 *  - Anyone can read comments.
 *  - Only signed-in users (a real userId) can comment, reply, like, edit or delete.
 *  - Replies are kept 2 levels deep: replying to a reply attaches it to the top comment.
 *  - A comment can be edited by its writer, and deleted by its writer or by the post's author.
 */
@RestController
@RequestMapping("/api/comments")
@CrossOrigin(origins = "*")
public class CommentController {

    private static final int MAX_LENGTH = 2000;

    private final CommentRepository commentRepository;
    private final CommentLikeRepository likeRepository;
    private final UserRepository userRepository;
    private final PostRepository postRepository;

    public CommentController(CommentRepository commentRepository,
                             CommentLikeRepository likeRepository,
                             UserRepository userRepository,
                             PostRepository postRepository) {
        this.commentRepository = commentRepository;
        this.likeRepository = likeRepository;
        this.userRepository = userRepository;
        this.postRepository = postRepository;
    }

    // ---------------- READ ----------------

    /** all comments of a post; pass ?userId= to get "likedByMe" for the signed-in user */
    @GetMapping("/post/{postId}")
    public List<Map<String, Object>> getCommentsByPost(@PathVariable Long postId,
                                                       @RequestParam(required = false) Long userId) {
        List<Comment> comments = commentRepository.findByPostIdOrderByCreatedAtAsc(postId);
        if (comments.isEmpty()) return new ArrayList<>();

        List<Long> ids = new ArrayList<>();
        Set<Long> userIds = new HashSet<>();
        for (Comment c : comments) {
            ids.add(c.getId());
            if (c.getUserId() != null) userIds.add(c.getUserId());
        }

        Map<Long, Long> likeCount = new HashMap<>();
        Set<Long> likedByMe = new HashSet<>();
        for (CommentLike l : likeRepository.findByCommentIdIn(ids)) {
            likeCount.merge(l.getCommentId(), 1L, Long::sum);
            if (userId != null && userId.equals(l.getUserId())) likedByMe.add(l.getCommentId());
        }

        Map<Long, User> users = new HashMap<>();
        for (User u : userRepository.findAllById(userIds)) users.put(u.getId(), u);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Comment c : comments) {
            rows.add(row(c, users.get(c.getUserId()),
                    likeCount.getOrDefault(c.getId(), 0L), likedByMe.contains(c.getId())));
        }
        return rows;
    }

    // ---------------- CREATE ----------------

    /** body: { postId, parentId (optional), userId, content } */
    @PostMapping
    public ResponseEntity<?> addComment(@RequestBody Map<String, Object> body) {
        Long postId = toLong(body.get("postId"));
        Long parentId = toLong(body.get("parentId"));
        Long userId = toLong(body.get("userId"));
        String content = body.get("content") == null ? "" : body.get("content").toString().trim();

        Optional<User> user = userId == null ? Optional.empty() : userRepository.findById(userId);
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Please sign in to comment."));
        }
        if (postId == null || !postRepository.existsById(postId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "This post no longer exists."));
        }
        if (content.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Write something first."));
        }
        if (content.length() > MAX_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("message", "Comments can be up to " + MAX_LENGTH + " characters."));
        }

        // keep threads 2 levels deep, like Facebook
        if (parentId != null) {
            Optional<Comment> parent = commentRepository.findById(parentId);
            if (parent.isEmpty() || !parent.get().getPostId().equals(postId)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "The comment you replied to was deleted."));
            }
            if (parent.get().getParentId() != null) parentId = parent.get().getParentId();
        }

        Comment c = new Comment();
        c.setPostId(postId);
        c.setParentId(parentId);
        c.setUserId(user.get().getId());
        c.setAuthorName(user.get().getUsername());
        c.setContent(content);
        c.setCreatedAt(LocalDateTime.now());
        c.setEdited(false);
        Comment saved = commentRepository.save(c);

        return ResponseEntity.status(HttpStatus.CREATED).body(row(saved, user.get(), 0L, false));
    }

    // ---------------- EDIT (writer only) ----------------

    /** body: { userId, content } — POST and PUT both accepted */
    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.POST})
    public ResponseEntity<?> editComment(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long userId = toLong(body.get("userId"));
        String content = body.get("content") == null ? "" : body.get("content").toString().trim();

        Optional<Comment> found = commentRepository.findById(id);
        if (found.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Comment not found"));
        Optional<User> user = userId == null ? Optional.empty() : userRepository.findById(userId);
        if (user.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Please sign in again."));

        Comment c = found.get();
        if (!isWriter(c, user.get())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "You can only edit your own comments."));
        }
        if (content.isEmpty()) return ResponseEntity.badRequest().body(Map.of("message", "A comment can’t be empty."));
        if (content.length() > MAX_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("message", "Comments can be up to " + MAX_LENGTH + " characters."));
        }

        c.setContent(content);
        c.setEdited(true);
        c.setUpdatedAt(LocalDateTime.now());
        if (c.getUserId() == null) c.setUserId(user.get().getId());
        Comment saved = commentRepository.save(c);

        long likes = likeRepository.countByCommentId(id);
        boolean liked = likeRepository.findByCommentIdAndUserId(id, userId).isPresent();
        return ResponseEntity.ok(row(saved, user.get(), likes, liked));
    }

    // ---------------- DELETE (writer or post author) ----------------

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> deleteComment(@PathVariable Long id, @RequestParam Long userId) {
        Optional<Comment> found = commentRepository.findById(id);
        if (found.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Comment not found"));
        Optional<User> user = userRepository.findById(userId);
        if (user.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Please sign in again."));

        Comment c = found.get();
        boolean postAuthor = postRepository.findById(c.getPostId())
                .map(Post::getAuthorId).map(a -> a.equals(userId)).orElse(false);
        if (!isWriter(c, user.get()) && !postAuthor) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "You can’t delete this comment."));
        }

        // the comment, its replies, and all their likes
        List<Long> ids = new ArrayList<>();
        ids.add(id);
        List<Comment> replies = commentRepository.findByParentId(id);
        for (Comment r : replies) ids.add(r.getId());
        likeRepository.deleteByCommentIdIn(ids);
        commentRepository.deleteAll(replies);
        commentRepository.delete(c);

        return ResponseEntity.ok(Map.of("message", "Comment deleted", "deleted", ids.size()));
    }

    // ---------------- LIKE (toggle) ----------------

    /** body: { userId } — likes the comment, or removes the like if already liked */
    @PostMapping("/{id}/like")
    @Transactional
    public ResponseEntity<?> toggleLike(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long userId = toLong(body.get("userId"));
        if (userId == null || !userRepository.existsById(userId)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Please sign in to like comments."));
        }
        if (!commentRepository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Comment not found"));
        }

        Optional<CommentLike> existing = likeRepository.findByCommentIdAndUserId(id, userId);
        boolean liked;
        if (existing.isPresent()) {
            likeRepository.delete(existing.get());
            liked = false;
        } else {
            CommentLike l = new CommentLike();
            l.setCommentId(id);
            l.setUserId(userId);
            l.setCreatedAt(LocalDateTime.now());
            likeRepository.save(l);
            liked = true;
        }
        long likes = likeRepository.countByCommentId(id);
        return ResponseEntity.ok(Map.of("liked", liked, "likes", likes));
    }

    // ---------------- helpers ----------------

    /** comment writer: same userId, or (old comments without userId) same username */
    private boolean isWriter(Comment c, User u) {
        if (c.getUserId() != null) return c.getUserId().equals(u.getId());
        return u.getUsername() != null && u.getUsername().equalsIgnoreCase(c.getAuthorName());
    }

    private Map<String, Object> row(Comment c, User writer, long likes, boolean likedByMe) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("postId", c.getPostId());
        m.put("parentId", c.getParentId());
        m.put("userId", c.getUserId());
        m.put("authorName", writer != null ? writer.getUsername() : c.getAuthorName());
        m.put("authorPicture", writer != null ? writer.getPictureUrl() : null);
        m.put("content", c.getContent());
        m.put("createdAt", c.getCreatedAt());
        m.put("edited", Boolean.TRUE.equals(c.getEdited()));
        m.put("likes", likes);
        m.put("likedByMe", likedByMe);
        return m;
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        try {
            String s = o.toString().trim();
            return s.isEmpty() ? null : Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}