package com.possaas.modules.menu;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.GlobalExceptionHandler;
import com.possaas.modules.menu.controller.MenuImageController;
import com.possaas.modules.menu.service.MenuImageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MenuImageControllerTest {
    private final MenuImageService images = mock(MenuImageService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MenuImageController(images))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    private final MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[] {1});

    @Test
    void returnsTheStoredUrl() throws Exception {
        when(images.upload(any())).thenReturn("https://cdn.test/a.jpg");
        mvc.perform(multipart("/api/v1/menu/images").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value("https://cdn.test/a.jpg"));
    }

    @Test
    void aRequestWithoutTheFileIsInvalid() throws Exception {
        mvc.perform(multipart("/api/v1/menu/images"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/menu/images"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void storageErrorsKeepTheirCode() throws Exception {
        when(images.upload(any())).thenThrow(
                new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "IMAGE_STORAGE_NOT_CONFIGURED", "Not configured"));
        mvc.perform(multipart("/api/v1/menu/images").file(file))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IMAGE_STORAGE_NOT_CONFIGURED"));
    }
}
