package com.possaas.modules.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.CurrentUser;
import com.possaas.infrastructure.storage.ImageStorage;
import com.possaas.modules.menu.service.MenuAccessService;
import com.possaas.modules.menu.service.MenuImageService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

class MenuImageServiceTest {
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
    private final MenuAccessService access = mock(MenuAccessService.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    private final MenuImageService service = new MenuImageService(access, storage);
    private final UUID restaurantId = UUID.randomUUID();

    @BeforeEach
    void signedIn() {
        var actor = mock(CurrentUser.class);
        when(actor.restaurantId()).thenReturn(restaurantId);
        when(access.require(false)).thenReturn(actor);
        when(storage.upload(any(), anyString())).thenReturn("https://cdn.test/a.jpg");
    }

    private MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "a.jpg", "image/jpeg", content);
    }

    private void assertRejected(byte[] content, HttpStatus status, String code) {
        assertThatThrownBy(() -> service.upload(file(content)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(status);
                    assertThat(ex.getCode()).isEqualTo(code);
                });
        verify(storage, never()).upload(any(), anyString());
    }

    @Test
    void storesTheImageInTheRestaurantsOwnFolder() {
        assertThat(service.upload(file(JPEG))).isEqualTo("https://cdn.test/a.jpg");
        verify(storage).upload(JPEG, restaurantId + "/menu");
    }

    @Test
    void acceptsPngAndWebp() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0};
        byte[] webp = "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);
        service.upload(file(png));
        service.upload(file(webp));
        verify(storage).upload(eq(png), anyString());
        verify(storage).upload(eq(webp), anyString());
    }

    @Test
    void judgesTheBytesNotTheDeclaredType() {
        assertRejected("<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8), HttpStatus.BAD_REQUEST, "INVALID_IMAGE");
        assertRejected("RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1), HttpStatus.BAD_REQUEST, "INVALID_IMAGE");
        assertRejected(new byte[0], HttpStatus.BAD_REQUEST, "INVALID_IMAGE");
    }

    @Test
    void refusesAnImageOverFiveMegabytes() {
        byte[] large = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(JPEG, 0, large, 0, JPEG.length);
        assertRejected(large, HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE");
    }

    @Test
    void accessIsCheckedBeforeTheFileIsTouched() {
        when(access.require(false)).thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "FEATURE_NOT_AVAILABLE", "No"));
        assertThatThrownBy(() -> service.upload(file(JPEG))).isInstanceOf(BusinessException.class);
        verify(storage, never()).upload(any(), anyString());
    }
}
