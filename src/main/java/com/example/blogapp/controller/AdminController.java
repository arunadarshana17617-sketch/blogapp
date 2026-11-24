package com.example.blogapp.controller;

import com.example.blogapp.model.Comment;
import com.example.blogapp.model.Post;
import com.example.blogapp.model.User;
import com.example.blogapp.repository.CommentRepository;
import com.example.blogapp.repository.PostRepository;
import com.example.blogapp.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Admin API used by admin.html (open it at http://localhost:8080/admin).
 *
 * Login:  POST /api/admin/login  {"username":"admin","password":"123"}
 * Every other endpoint needs the returned token in the "X-Admin-Token" header.
 * Credentials can be changed in application.properties (admin.username / admin.password).
 */
@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class AdminController {

    private static final Duration SESSION_LENGTH = Duration.ofHours(8);

    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final String adminUsername;
    private final String adminPassword;

    /** token -> expiry time (kept in memory; restarting the app logs the admin out) */
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();

    public AdminController(UserRepository userRepository,
                           PostRepository postRepository,
                           CommentRepository commentRepository,
                           @Value("${admin.username:admin}") String adminUsername,
                           @Value("${admin.password:123}") String adminPassword) {
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    // =====================================================================
    //  Auth
    // =====================================================================

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String u = body == null ? null : body.get("username");
        String p = body == null ? null : body.get("password");
        if (adminUsername.equals(u) && adminPassword.equals(p)) {
            String token = UUID.randomUUID().toString();
            sessions.put(token, Instant.now().plus(SESSION_LENGTH));
            return ResponseEntity.ok(Map.of("token", token, "username", adminUsername));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Wrong admin username or password"));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (token != null) sessions.remove(token);
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    @GetMapping("/check")
    public ResponseEntity<?> check(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!valid(token)) return unauthorized();
        return ResponseEntity.ok(Map.of("username", adminUsername));
    }

    private boolean valid(String token) {
        if (token == null) return false;
        Instant exp = sessions.get(token);
        if (exp == null) return false;
        if (exp.isBefore(Instant.now())) {
            sessions.remove(token);
            return false;
        }
        return true;
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", "Admin session expired. Please log in again."));
    }

    // =====================================================================
    //  Dashboard
    // =====================================================================

    @GetMapping("/stats")
    public ResponseEntity<?> stats(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!valid(token)) return unauthorized();

        List<Post> posts = postRepository.findAllByOrderByCreatedAtDesc();
        List<User> users = userRepository.findAll();
        long comments = commentRepository.count();

        Map<String, Long> byCategory = new TreeMap<>();
        for (Post p : posts) byCategory.merge(p.getCategory() == null ? "Other" : p.getCategory(), 1L, Long::sum);

        Map<String, Long> byAuthor = new HashMap<>();
        for (Post p : posts) byAuthor.merge(p.getAuthorName() == null ? "Unknown" : p.getAuthorName(), 1L, Long::sum);
        List<Map<String, Object>> topAuthors = byAuthor.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("posts", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("users", users.size());
        m.put("googleUsers", users.stream().filter(u -> u.getGoogleId() != null).count());
        m.put("posts", posts.size());
        m.put("comments", comments);
        m.put("postsWithPhoto", posts.stream().filter(p -> p.getImageUrl() != null).count());
        m.put("byCategory", byCategory);
        m.put("topAuthors", topAuthors);
        m.put("recentPosts", posts.stream().limit(5).map(p -> postRow(p, null)).collect(Collectors.toList()));
        return ResponseEntity.ok(m);
    }

    // =====================================================================
    //  Posts
    // =====================================================================

    @GetMapping("/posts")
    public ResponseEntity<?> posts(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!valid(token)) return unauthorized();
        Map<Long, Long> commentCounts = commentCountByPost();
        return ResponseEntity.ok(postRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(p -> postRow(p, commentCounts))
                .collect(Collectors.toList()));
    }

    /** admin can edit any post (POST and PUT both accepted, multipart form) */
    @RequestMapping(value = "/posts/{id}", method = {RequestMethod.PUT, RequestMethod.POST})
    public ResponseEntity<?> updatePost(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                        @PathVariable Long id,
                                        @RequestParam String title,
                                        @RequestParam String content,
                                        @RequestParam String category,
                                        @RequestParam(required = false) MultipartFile photo,
                                        @RequestParam(defaultValue = "false") boolean removePhoto) {
        if (!valid(token)) return unauthorized();
        Optional<Post> found = postRepository.findById(id);
        if (found.isEmpty()) return notFound("Post not found");
        if (title.isBlank() || content.isBlank() || category.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Title, category, and content are required"));
        }
        Post post = found.get();
        post.setTitle(title);
        post.setContent(content);
        post.setCategory(category);

        if (photo != null && !photo.isEmpty()) {
            try {
                String newUrl = savePhoto(photo);
                deletePhotoFile(post.getImageUrl());
                post.setImageUrl(newUrl);
            } catch (IOException e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Failed to upload photo"));
            }
        } else if (removePhoto) {
            deletePhotoFile(post.getImageUrl());
            post.setImageUrl(null);
        }
        return ResponseEntity.ok(postRow(postRepository.save(post), commentCountByPost()));
    }

    @DeleteMapping("/posts/{id}")
    @Transactional
    public ResponseEntity<?> deletePost(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                        @PathVariable Long id) {
        if (!valid(token)) return unauthorized();
        Optional<Post> found = postRepository.findById(id);
        if (found.isEmpty()) return notFound("Post not found");
        removePost(found.get());
        return ResponseEntity.ok(Map.of("message", "Post deleted"));
    }

    // =====================================================================
    //  Users
    // =====================================================================

    @GetMapping("/users")
    public ResponseEntity<?> users(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!valid(token)) return unauthorized();
        List<Post> posts = postRepository.findAll();
        List<Comment> comments = commentRepository.findAll();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (User u : userRepository.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("email", u.getEmail());
            m.put("picture", u.getPictureUrl());
            m.put("loginType", u.getGoogleId() != null ? "Google" : "Password");
            m.put("posts", posts.stream().filter(p -> Objects.equals(p.getAuthorId(), u.getId())).count());
            m.put("comments", comments.stream().filter(c -> u.getUsername() != null && u.getUsername().equalsIgnoreCase(c.getAuthorName())).count());
            rows.add(m);   // password is never sent
        }
        rows.sort((a, b) -> Long.compare((Long) b.get("id"), (Long) a.get("id")));   // newest first
        return ResponseEntity.ok(rows);
    }

    /** set a new password for a username/password user */
    @PostMapping("/users/{id}/password")
    public ResponseEntity<?> resetPassword(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                           @PathVariable Long id,
                                           @RequestBody Map<String, String> body) {
        if (!valid(token)) return unauthorized();
        Optional<User> found = userRepository.findById(id);
        if (found.isEmpty()) return notFound("User not found");
        String pw = body == null ? null : body.get("password");
        if (pw == null || pw.length() < 4) {
            return ResponseEntity.badRequest().body(Map.of("message", "Password must be at least 4 characters"));
        }
        User u = found.get();
        u.setPassword(pw);
        userRepository.save(u);
        return ResponseEntity.ok(Map.of("message", "Password updated"));
    }

    /** delete a user; their posts (and those posts' comments) are deleted too when deletePosts=true */
    @DeleteMapping("/users/{id}")
    @Transactional
    public ResponseEntity<?> deleteUser(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                        @PathVariable Long id,
                                        @RequestParam(defaultValue = "true") boolean deletePosts) {
        if (!valid(token)) return unauthorized();
        Optional<User> found = userRepository.findById(id);
        if (found.isEmpty()) return notFound("User not found");

        int removed = 0;
        if (deletePosts) {
            for (Post p : postRepository.findByAuthorIdOrderByCreatedAtDesc(id)) {
                removePost(p);
                removed++;
            }
        }
        userRepository.delete(found.get());
        return ResponseEntity.ok(Map.of("message", "User deleted", "postsDeleted", removed));
    }

    // =====================================================================
    //  Comments
    // =====================================================================

    @GetMapping("/comments")
    public ResponseEntity<?> comments(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!valid(token)) return unauthorized();
        Map<Long, String> titles = new HashMap<>();
        for (Post p : postRepository.findAll()) titles.put(p.getId(), p.getTitle());

        List<Comment> all = new ArrayList<>(commentRepository.findAll());
        all.sort((a, b) -> {                 // newest first, nulls last
            if (a.getCreatedAt() == null) return 1;
            if (b.getCreatedAt() == null) return -1;
            return b.getCreatedAt().compareTo(a.getCreatedAt());
        });

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Comment c : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("postId", c.getPostId());
            m.put("postTitle", titles.getOrDefault(c.getPostId(), "(deleted post)"));
            m.put("parentId", c.getParentId());
            m.put("authorName", c.getAuthorName());
            m.put("content", c.getContent());
            m.put("createdAt", c.getCreatedAt());
            rows.add(m);
        }
        return ResponseEntity.ok(rows);
    }

    /** deletes the comment and any replies under it */
    @DeleteMapping("/comments/{id}")
    @Transactional
    public ResponseEntity<?> deleteComment(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                           @PathVariable Long id) {
        if (!valid(token)) return unauthorized();
        if (!commentRepository.existsById(id)) return notFound("Comment not found");
        commentRepository.deleteByParentId(id);
        commentRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Comment deleted"));
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    private ResponseEntity<?> notFound(String msg) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", msg));
    }

    private void removePost(Post p) {
        commentRepository.deleteByPostId(p.getId());
        postRepository.delete(p);
        deletePhotoFile(p.getImageUrl());
    }

    private Map<Long, Long> commentCountByPost() {
        Map<Long, Long> counts = new HashMap<>();
        for (Comment c : commentRepository.findAll()) counts.merge(c.getPostId(), 1L, Long::sum);
        return counts;
    }

    private Map<String, Object> postRow(Post p, Map<Long, Long> commentCounts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("title", p.getTitle());
        m.put("content", p.getContent());
        m.put("category", p.getCategory());
        m.put("imageUrl", p.getImageUrl());
        m.put("authorId", p.getAuthorId());
        m.put("authorName", p.getAuthorName());
        m.put("createdAt", p.getCreatedAt());
        m.put("comments", commentCounts == null ? 0L : commentCounts.getOrDefault(p.getId(), 0L));
        return m;
    }

    private String savePhoto(MultipartFile photo) throws IOException {
        Path uploadPath = Paths.get("uploads");
        Files.createDirectories(uploadPath);
        String original = Path.of(photo.getOriginalFilename()).getFileName().toString();
        String filename = UUID.randomUUID() + "_" + original;
        Files.copy(photo.getInputStream(), uploadPath.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
        return "/uploads/" + filename;
    }

    private void deletePhotoFile(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith("/uploads/")) return;
        try {
            String filename = Path.of(imageUrl.substring("/uploads/".length())).getFileName().toString();
            Files.deleteIfExists(Paths.get("uploads").resolve(filename));
        } catch (Exception ignored) {
            // file missing or locked — database change still goes through
        }
    }
}