package com.example.dormitory.config;

import com.example.dormitory.domain.UserAccount;
import com.example.dormitory.mapper.UserAccountMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BootstrapAdminInitializerTest {

    @Test
    void blankBootstrapCredentialsKeepBootstrapDisabled() {
        UserAccountMapper users = mock(UserAccountMapper.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);

        new BootstrapAdminInitializer(users, passwords, "", "", "系统管理员").run(null);

        verifyNoInteractions(users, passwords);
    }

    @Test
    void configuredBootstrapCredentialsMustMeetTheOrdinaryAccountMinimumContract() {
        for (Credentials credentials : List.of(
                new Credentials("ab", "valid-password"),
                new Credentials("invalid user", "valid-password"),
                new Credentials("a".repeat(33), "valid-password"),
                new Credentials("valid-admin", "short7"),
                new Credentials("valid-admin", "p".repeat(65)))) {
            UserAccountMapper users = mock(UserAccountMapper.class);
            PasswordEncoder passwords = mock(PasswordEncoder.class);

            assertThrows(IllegalStateException.class,
                    () -> new BootstrapAdminInitializer(users, passwords,
                            credentials.username(), credentials.password(), "系统管理员").run(null));
            verifyNoInteractions(users, passwords);
        }
    }

    @Test
    void validBootstrapCredentialsStillCreateTheInitialAdmin() {
        UserAccountMapper users = mock(UserAccountMapper.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(users.selectCount(any())).thenReturn(0L);
        when(passwords.encode("valid-password")).thenReturn("encoded-password");

        new BootstrapAdminInitializer(
                users, passwords, "valid-admin", "valid-password", "系统管理员").run(null);

        verify(passwords).encode("valid-password");
        verify(users).insert(any(UserAccount.class));
    }

    @Test
    void anExistingAdminIsNotRewritten() {
        UserAccountMapper users = mock(UserAccountMapper.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(users.selectCount(any())).thenReturn(1L);

        new BootstrapAdminInitializer(
                users, passwords, "valid-admin", "valid-password", "系统管理员").run(null);

        verify(passwords, never()).encode(any());
        verify(users, never()).insert(any(UserAccount.class));
    }

    private record Credentials(String username, String password) { }
}
