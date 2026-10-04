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
public class UserShowLimitId implements Serializable {

    @Column(name = "show_id")
    private UUID showId;

    @Column(name = "user_id")
    private String userId;
}