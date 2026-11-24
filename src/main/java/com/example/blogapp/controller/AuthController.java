package com.example.blogapp.controller;

import com.example.blogapp.model.User;
import com.example.blogapp.repository.UserRepository;
import com.example.blogapp.service.GoogleTokenVerifier;
import com.example.blogapp.service.GoogleTokenVerifier.GoogleUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private final UserRepository userRepository;
    private final GoogleTokenVerifier googleVerifier;
    private final SecureRandom random = new SecureRandom();

    public AuthController(UserRepository userRepository, GoogleTokenVerifier googleVerifier) {
        this.userRepository = userRepository;
        this.googleVerifier = googleVerifier;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody User request) {
        if (request.getUsername() == null || request.getUsername().isBlank()
                || request.getPassword() == null || request.getPassword().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Username and password required"));
        }
        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Username already exists"));
        }
        User fresh = new User();
        fresh.setUsername(request.getUsername().trim());
        fresh.setPassword(request.getPassword());
        User saved = userRepository.save(fresh);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("id", saved.getId(), "username", saved.getUsername()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody User request) {
        Optional<User> userOpt = request.getUsername() == null
                ? Optional.empty()
                : userRepository.findByUsername(request.getUsername());
        if (userOpt.isPresent() && userOpt.get().getPassword().equals(request.getPassword())) {
            return ResponseEntity.ok(userResponse(userOpt.get(), "Login successful"));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Invalid credentials"));
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("googleClientId", googleVerifier.isConfigured() ? googleVerifier.getClientId() : "");
        return m;
    }

    @PostMapping("/google")
    public ResponseEntity<?> googleLogin(@RequestBody Map<String, String> body) {
        String credential = body == null ? null : body.get("credential");
        if (credential == null || credential.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Missing Google credential"));
        }
        if (!googleVerifier.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "Google login is not configured on the server"));
        }

        GoogleUser g;
        try {
            g = googleVerifier.verify(credential);
        } catch (GeneralSecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Google sign-in failed: " + e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("message", "Could not reach Google to verify sign-in. Try again."));
        }

        if (!g.emailVerified()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Your Google email is not verified"));
        }

        User user = userRepository.findByGoogleId(g.sub())
                .or(() -> userRepository.findByEmail(g.email()))
                .orElseGet(User::new);

        boolean isNew = user.getId() == null;
        user.setGoogleId(g.sub());
        user.setEmail(g.email());
        if (!g.picture().isBlank()) user.setPictureUrl(g.picture());
        if (isNew) {
            user.setUsername(uniqueUsername(g));
            user.setPassword(UUID.randomUUID() + "-" + UUID.randomUUID());
        }
        user = userRepository.save(user);

        return ResponseEntity.ok(userResponse(user, isNew ? "Account created with Google" : "Login successful"));
    }

    private String uniqueUsername(GoogleUser g) {
        String base = g.name().isBlank() ? g.email().substring(0, g.email().indexOf('@')) : g.name().trim();
        if (base.length() > 40) base = base.substring(0, 40);
        String candidate = base;
        while (userRepository.findByUsername(candidate).isPresent()) {
            candidate = base + " " + (1000 + random.nextInt(9000));
        }
        return candidate;
    }

    private Map<String, Object> userResponse(User user, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", message);
        m.put("userId", user.getId());
        m.put("username", user.getUsername());
        m.put("email", user.getEmail());
        m.put("picture", user.getPictureUrl());
        return m;
    }
}