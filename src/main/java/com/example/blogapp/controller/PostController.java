package com.example.blogapp.controller;

import com.example.blogapp.model.Post;
import com.example.blogapp.model.User;
import com.example.blogapp.repository.CommentRepository;
import com.example.blogapp.repository.PostRepository;
import com.example.blogapp.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/posts")
@CrossOrigin(origins = "*")
public class PostController {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final UserRepository userRepository;

    public PostController(PostRepository postRepository, CommentRepository commentRepository,
                          UserRepository userRepository) {
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.userRepository = userRepository;
    }

    // ---------------- READ ----------------

    @GetMapping
    public List<Post> getPosts(@RequestParam(required = false) String category) {
        if (category != null && !category.isBlank() && !category.equalsIgnoreCase("ALL")) {
            return postRepository.findByCategoryIgnoreCaseOrderByCreatedAtDesc(category);
        }
        return postRepository.findAllByOrderByCreatedAtDesc();
    }

    /*
     * All posts written by one user (used by my-posts.html).
     *
     * Posts are matched by authorId. Posts that carry this user's exact username but
     * point to an account that no longer exists (e.g. the users table was cleared and
     * the person signed up again) are linked back to this account, so they show up
     * and can be edited/deleted. Usernames are unique, so this never takes someone
     * else's posts.
     */
    @GetMapping("/author/{authorId}")
    @Transactional
    public ResponseEntity<?> getPostsByAuthor(@PathVariable Long authorId) {
        Optional<User> found = userRepository.findById(authorId);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Your account was not found. Sign out and sign in again."));
        }
        User me = found.get();

        for (Post p : postRepository.findByAuthorNameIgnoreCase(me.getUsername())) {
            if (Objects.equals(p.getAuthorId(), me.getId())) continue;
            boolean ownerGone = p.getAuthorId() == null || !userRepository.existsById(p.getAuthorId());
            if (ownerGone) {
                p.setAuthorId(me.getId());
                postRepository.save(p);
            }
        }

        return ResponseEntity.ok(postRepository.findByAuthorIdOrderByCreatedAtDesc(me.getId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getPost(@PathVariable Long id) {
        return postRepository.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Post not found")));
    }

    // ---------------- CREATE ----------------

    @PostMapping
    public ResponseEntity<?> createPost(
            @RequestParam String title,
            @RequestParam String content,
            @RequestParam String category,
            @RequestParam Long authorId,
            @RequestParam String authorName,
            @RequestParam(required = false) MultipartFile photo
    ) {
        if (title.isBlank() || content.isBlank() || category.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Title, category, and content are required"));
        }

        String imageUrl = null;
        if (photo != null && !photo.isEmpty()) {
            try {
                imageUrl = savePhoto(photo);
            } catch (IOException e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("message", "Failed to upload photo"));
            }
        }

        Post post = new Post();
        post.setTitle(title);
        post.setContent(content);
        post.setCategory(category);
        post.setImageUrl(imageUrl);
        post.setAuthorId(authorId);
        post.setAuthorName(authorName);
        post.setCreatedAt(LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.CREATED).body(postRepository.save(post));
    }

    // ---------------- UPDATE (only the author) ----------------

    @PutMapping("/{id}")
    public ResponseEntity<?> updatePost(
            @PathVariable Long id,
            @RequestParam String title,
            @RequestParam String content,
            @RequestParam String category,
            @RequestParam Long authorId,
            @RequestParam(required = false) MultipartFile photo,
            @RequestParam(defaultValue = "false") boolean removePhoto
    ) {
        Optional<Post> found = postRepository.findById(id);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Post not found"));
        }
        Post post = found.get();

        if (!Objects.equals(post.getAuthorId(), authorId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "You can only edit your own posts"));
        }
        if (title.isBlank() || content.isBlank() || category.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Title, category, and content are required"));
        }

        post.setTitle(title);
        post.setContent(content);
        post.setCategory(category);

        // new photo replaces the old one; removePhoto clears it
        if (photo != null && !photo.isEmpty()) {
            try {
                String newUrl = savePhoto(photo);
                deletePhotoFile(post.getImageUrl());
                post.setImageUrl(newUrl);
            } catch (IOException e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("message", "Failed to upload photo"));
            }
        } else if (removePhoto) {
            deletePhotoFile(post.getImageUrl());
            post.setImageUrl(null);
        }

        return ResponseEntity.ok(postRepository.save(post));
    }

    // ---------------- DELETE (only the author) ----------------

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> deletePost(@PathVariable Long id, @RequestParam Long authorId) {
        Optional<Post> found = postRepository.findById(id);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Post not found"));
        }
        Post post = found.get();

        if (!Objects.equals(post.getAuthorId(), authorId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "You can only delete your own posts"));
        }

        commentRepository.deleteByPostId(id);   // remove the post's comments + replies
        postRepository.delete(post);
        deletePhotoFile(post.getImageUrl());

        return ResponseEntity.ok(Map.of("message", "Post deleted"));
    }

    // ---------------- helpers ----------------

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
            // file missing or locked — the database change still goes through
        }
    }
}