package com.fip.ingestion.adapters.in.rest;

import com.fip.common.response.StandardResponse;
import com.fip.ingestion.application.service.MappingProfileService;
import com.fip.ingestion.domain.model.MappingProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ingestion/mappings")
@Tag(name = "Source Mapping Profiles", description = "Tenant-scoped declarative transformation profiles for external formats")
public class MappingProfileController {

    private final MappingProfileService profileService;

    public MappingProfileController(MappingProfileService profileService) {
        this.profileService = profileService;
    }

    @PostMapping
    @Operation(summary = "Create declarative mapping profile")
    public ResponseEntity<StandardResponse<MappingProfile>> createProfile(@RequestBody MappingProfile profile) {
        MappingProfile created = profileService.createProfile(profile);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(StandardResponse.success(201, created, "Source mapping profile registered successfully"));
    }

    @GetMapping("/{profileId}")
    @Operation(summary = "Get mapping profile by ID")
    public ResponseEntity<StandardResponse<MappingProfile>> getProfile(@PathVariable UUID profileId) {
        return profileService.getProfile(profileId)
                .map(p -> ResponseEntity.ok(StandardResponse.success(200, p, "Mapping profile retrieved successfully")))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(StandardResponse.<MappingProfile>builder()
                                .success(false)
                                .status(404)
                                .message("Mapping profile not found: " + profileId)
                                .build()));
    }

    @GetMapping
    @Operation(summary = "List all active mapping profiles for caller tenant")
    public ResponseEntity<StandardResponse<List<MappingProfile>>> listProfiles() {
        List<MappingProfile> list = profileService.listProfiles();
        return ResponseEntity.ok(StandardResponse.success(200, list, "Mapping profiles retrieved successfully"));
    }
}
