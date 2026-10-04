package com.kiran.seatreservation.controller;

import com.kiran.seatreservation.dto.ShowResponse;
import com.kiran.seatreservation.dto.request.CreateShowRequest;
import com.kiran.seatreservation.service.ShowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/shows")
@RequiredArgsConstructor
public class ShowController {

    private final ShowService showService;

    @PostMapping
    public ResponseEntity<ShowResponse> createShow(
            @Valid @RequestBody CreateShowRequest request
    ) {

        ShowResponse response = showService.createShow(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/{showId}")
    public ResponseEntity<ShowResponse> getShow(
            @PathVariable UUID showId
    ) {

        return ResponseEntity.ok(
                showService.getShow(showId)
        );
    }
}