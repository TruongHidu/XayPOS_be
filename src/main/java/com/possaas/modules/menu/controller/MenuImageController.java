package com.possaas.modules.menu.controller;

import com.possaas.modules.menu.dto.MenuImageResponse;
import com.possaas.modules.menu.service.MenuImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/menu/images")
public class MenuImageController {
    private final MenuImageService images;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('MENU_CREATE','MENU_UPDATE')")
    public MenuImageResponse upload(@RequestPart("file") MultipartFile file) {
        return new MenuImageResponse(images.upload(file));
    }
}
