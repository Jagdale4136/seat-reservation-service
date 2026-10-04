package com.kiran.seatreservation.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "user_show_limits")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserShowLimit {

    @EmbeddedId
    private UserShowLimitId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("showId")
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    @Column(name = "active_seat_count", nullable = false)
    @Builder.Default
    private Integer activeSeatCount = 0;
}