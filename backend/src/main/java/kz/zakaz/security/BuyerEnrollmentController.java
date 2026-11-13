package kz.zakaz.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/**
 * Buyer self-enrollment is disabled globally until a reviewed real-IdP deployment.
 * No anonymous account registration, phone verification or SMS authentication here.
 */
@RestController
@ConditionalOnProperty(name="zakaz.buyer.enrollment.enabled",havingValue="true")
@RequestMapping("/api/v1/buyers")
public class BuyerEnrollmentController {
    public record EnrollmentInput(@NotBlank String nickname,
                                  @NotBlank String phone,
                                  @NotNull Boolean consentToStorePhone) {}
    private final BuyerEnrollmentService service;
    public BuyerEnrollmentController(BuyerEnrollmentService service) {this.service=service;}

    @GetMapping("/me")
    public BuyerEnrollmentService.Profile me(JwtAuthenticationToken token){
        return service.me(token);
    }
    @PostMapping("/enroll")
    public ResponseEntity<BuyerEnrollmentService.Profile> enroll(
        JwtAuthenticationToken token,@Valid @RequestBody EnrollmentInput input){
        var profile=service.enroll(token,input.nickname(),input.phone(),
                                   Boolean.TRUE.equals(input.consentToStorePhone()));
        return ResponseEntity.status(profile.created()?HttpStatus.CREATED:HttpStatus.OK)
            .body(profile);
    }
}
