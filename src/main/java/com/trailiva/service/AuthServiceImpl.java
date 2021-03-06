package com.trailiva.service;

import com.trailiva.data.model.Token;
import com.trailiva.data.model.TokenType;
import com.trailiva.data.model.User;
import com.trailiva.data.repository.TokenRepository;
import com.trailiva.data.repository.UserRepository;
import com.trailiva.security.CustomUserDetailService;
import com.trailiva.security.JwtTokenProvider;
import com.trailiva.security.UserPrincipal;
import com.trailiva.web.exceptions.AuthException;
import com.trailiva.web.exceptions.TokenException;
import com.trailiva.web.exceptions.UserVerificationException;
import com.trailiva.web.payload.request.*;
import com.trailiva.web.payload.response.JwtTokenResponse;
import com.trailiva.web.payload.response.UserResponse;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.time.LocalDateTime;
import java.util.UUID;

import static java.lang.String.format;

@Service
@Slf4j
public class AuthServiceImpl implements AuthService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ModelMapper modelMapper;

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private CustomUserDetailService customUserDetailService;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private TokenRepository tokenRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private EmailService emailService;


    @Override
    public UserResponse register(UserRequest userRequest, String siteUrl) throws AuthException, MessagingException, UnsupportedEncodingException {
        if (validateEmail(userRequest.getEmail())) {
            throw new AuthException("Email is already in use");
        }
        User user = modelMapper.map(userRequest, User.class);
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setVerificationCode(UUID.randomUUID().toString());
        User savedUser = save(user);

        // Setup Email verification
        EmailRequest emailRequest = modelMapper.map(savedUser, EmailRequest.class);
        emailService.sendUserVerificationEmail(emailRequest);
        return modelMapper.map(savedUser, UserResponse.class);
    }

    @Override
    public JwtTokenResponse login(LoginRequest loginRequest) {
        final Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        loginRequest.getEmail(),
                        loginRequest.getPassword()
                )
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        final UserPrincipal userDetails = (UserPrincipal) customUserDetailService.loadUserByUsername(loginRequest.getEmail());
        final String token = jwtTokenProvider.generateToken(userDetails);
        User user = internalFindUserByEmail(loginRequest.getEmail());
        return new JwtTokenResponse(token, user.getEmail());
    }

    private User internalFindUserByEmail(String email) {
        return userRepository.findByEmail(email).orElse(null);
    }

    @Override
    public void updatePassword(PasswordRequest request) throws AuthException {
        String email = request.getEmail();
        String oldPassword = request.getOldPassword();
        String newPassword = request.getPassword();
        User userToChangePassword = userRepository.findByEmail(email)
                .orElseThrow(() -> new AuthException("No user found with email" + email));

        boolean passwordMatch = passwordEncoder.matches(oldPassword, userToChangePassword.getPassword());
        if (!passwordMatch) {
            throw new AuthException("Passwords do not match");
        }
        userToChangePassword.setPassword(passwordEncoder.encode(newPassword));
        save(userToChangePassword);
    }

    @Override
    public void resetPassword(ResetPasswordRequest request, String passwordResetToken) throws AuthException, TokenException {
        String email = request.getEmail();
        String newPassword = request.getPassword();
        User userToResetPassword = userRepository.findByEmail(email)
                .orElseThrow(() -> new AuthException("No user found with user name " + email));
        Token token = tokenRepository.findByToken(passwordResetToken)
                .orElseThrow(() -> new TokenException(format("No token with value %s found", passwordResetToken)));
        if (token.getExpiry().isBefore(LocalDateTime.now())) {
            throw new TokenException("This password reset token has expired ");
        }
        if (!token.getUserId().equals(userToResetPassword.getUserId())) {
            throw new TokenException("This password rest token does not belong to this user");
        }
        userToResetPassword.setPassword(passwordEncoder.encode(newPassword));
        save(userToResetPassword);
        tokenRepository.delete(token);
    }

    @Override
    public Token generatePasswordResetToken(String email) throws AuthException {
        User userToResetPassword = userRepository.findByEmail(email)
                .orElseThrow(() -> new AuthException("No user found with user name " + email));
        Token token = new Token();
        token.setType(TokenType.PASSWORD_RESET);
        token.setUserId(userToResetPassword.getUserId());
        token.setToken(UUID.randomUUID().toString());
        token.setExpiry(LocalDateTime.now().plusMinutes(30));
        return tokenRepository.save(token);
    }

    private boolean validateEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    private User save(User user) {
       return userRepository.save(user);
    }

    @Override
    public boolean verify(String verificationToken) throws UserVerificationException {
        User user = userRepository.findByVerificationCode(verificationToken).orElseThrow(
                () -> new UserVerificationException(format("No user found with verification code %s", verificationToken)));
        if (user.isEnabled())
            return false;
        else {
            user.setVerificationCode(null);
            user.setEnabled(true);
            save(user);
            return true;
        }
    }

}
