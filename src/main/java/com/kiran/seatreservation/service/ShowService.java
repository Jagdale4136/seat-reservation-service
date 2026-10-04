package com.kiran.seatreservation.service;

import com.kiran.seatreservation.dto.SeatResponse;
import com.kiran.seatreservation.dto.ShowResponse;
import com.kiran.seatreservation.dto.request.CreateShowRequest;
import com.kiran.seatreservation.entity.Seat;
import com.kiran.seatreservation.entity.Show;
import com.kiran.seatreservation.entity.enums.SeatStatus;
import com.kiran.seatreservation.exception.InvalidRequestException;
import com.kiran.seatreservation.exception.ResourceNotFoundException;
import com.kiran.seatreservation.repository.SeatRepository;
import com.kiran.seatreservation.repository.ShowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ShowService {

    // Matches seats.seat_number VARCHAR(50)
    private static final int MAX_SEAT_NUMBER_LENGTH = 50;

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;

    /**
     * Create a new show along with its seats.
     */
    @Transactional
    public ShowResponse createShow(CreateShowRequest request) {

        List<String> seatNumbers = normalizeSeatNumbers(request.seats());

        // Create show
        Show show = new Show();

        show.setName(request.name());
        show.setPricePaise(request.price_paise());

        Integer perUserLimit = request.per_user_limit() != null
                ? request.per_user_limit()
                : 4;

        show.setPerUserLimit(perUserLimit);

        // Save show first so we get the generated UUID
        Show savedShow = showRepository.save(show);

        // Create seats
        List<Seat> seats = new ArrayList<>();

        for (String seatNumber : seatNumbers) {

            Seat seat = new Seat();

            seat.setShow(savedShow);
            seat.setSeatNumber(seatNumber);
            seat.setStatus(SeatStatus.AVAILABLE);

            seats.add(seat);
        }

        // Save all seats
        List<Seat> savedSeats = seatRepository.saveAll(seats);

        // Return response
        return buildShowResponse(savedShow, savedSeats);
    }

    /**
     * Get complete show information including seats and their status.
     */
    @Transactional(readOnly = true)
    public ShowResponse getShow(UUID showId) {

        Show show = showRepository.findById(showId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Show not found: " + showId
                        )
                );

        List<Seat> seats =
                seatRepository.findByShowIdOrderBySeatNumber(showId);

        return buildShowResponse(show, seats);
    }

    /**
     * Trim seat numbers (the reserve path trims too, so " A1" would
     * otherwise be unreservable) and reject duplicates / oversized values.
     */
    private List<String> normalizeSeatNumbers(List<String> seats) {

        List<String> normalized = seats.stream()
                .map(String::trim)
                .toList();

        for (String seatNumber : normalized) {

            if (seatNumber.length() > MAX_SEAT_NUMBER_LENGTH) {
                throw new InvalidRequestException(
                        "Seat number must be at most "
                                + MAX_SEAT_NUMBER_LENGTH
                                + " characters: "
                                + seatNumber
                );
            }
        }

        if (normalized.stream().distinct().count() != normalized.size()) {
            throw new InvalidRequestException(
                    "Duplicate seat numbers are not allowed"
            );
        }

        return normalized;
    }

    /**
     * Build ShowResponse from Show and Seat entities.
     */
    private ShowResponse buildShowResponse(
            Show show,
            List<Seat> seats
    ) {

        int totalSeats = seats.size();

        int availableSeats = 0;
        int heldSeats = 0;
        int confirmedSeats = 0;

        List<SeatResponse> seatResponses = new ArrayList<>();

        for (Seat seat : seats) {

            if (seat.getStatus() == SeatStatus.AVAILABLE) {
                availableSeats++;
            } else if (seat.getStatus() == SeatStatus.HELD) {
                heldSeats++;
            } else if (seat.getStatus() == SeatStatus.CONFIRMED) {
                confirmedSeats++;
            }

            seatResponses.add(
                    SeatResponse.builder()
                            .seat(seat.getSeatNumber())
                            .status(seat.getStatus())
                            .build()
            );
        }

        return ShowResponse.builder()
                .id(show.getId())
                .name(show.getName())
                .pricePaise(show.getPricePaise())
                .perUserLimit(show.getPerUserLimit())
                .totalSeats(totalSeats)
                .availableSeats(availableSeats)
                .heldSeats(heldSeats)
                .confirmedSeats(confirmedSeats)
                .seats(seatResponses)
                .build();
    }
}