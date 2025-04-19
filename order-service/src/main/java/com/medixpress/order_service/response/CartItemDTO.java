package com.medixpress.order_service.response;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartItemDTO {
    private String id;
    private Long userId;
    private Long pharmacyId;
    private String medicineId;
    private Integer quantity;
    private LocalDateTime addedAt;
}
