package com.possaas.modules.qrorder.controller;

import com.possaas.modules.qrorder.dto.*;
import com.possaas.modules.qrorder.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/public/qr-order") @RequiredArgsConstructor
public class PublicQrOrderController {
    private final QrGuestAccessService access;
    private final QrGuestSubmissionService submissions;
    @PostMapping("/tables/{qrToken}/guest-access")
    ResponseEntity<QrGuestAccessResponse> tableAccess(@PathVariable String qrToken,@RequestBody QrGuestRequests.Empty body,HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(access.mintTable(qrToken,request.getRemoteAddr()));
    }
    @PostMapping("/restaurants/{menuToken}/guest-access")
    ResponseEntity<QrGuestAccessResponse> restaurantAccess(@PathVariable String menuToken,@RequestBody QrGuestRequests.Empty body,HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(access.mintRestaurant(menuToken,request.getRemoteAddr()));
    }
    @PostMapping("/requests")
    ResponseEntity<QrPublicResponse> submit(@RequestHeader("X-QR-Guest-Token") String token,@RequestHeader("Idempotency-Key") String key,
            @RequestBody QrGuestRequests.Submit body,HttpServletRequest request) {
        var result=submissions.submit(token,key,body,request.getRemoteAddr());
        return ResponseEntity.status(result.replayed()?HttpStatus.OK:HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.response());
    }
    @GetMapping("/requests/{requestId}")
    ResponseEntity<QrPublicResponse> own(@RequestHeader("X-QR-Guest-Token") String token,@PathVariable UUID requestId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(submissions.findOwn(token,requestId));
    }
}
