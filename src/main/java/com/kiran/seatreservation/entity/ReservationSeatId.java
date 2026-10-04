package com.kiran.seatreservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ReservationSeatId implements Serializable {

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "seat_id")
    private UUID seatId;
}