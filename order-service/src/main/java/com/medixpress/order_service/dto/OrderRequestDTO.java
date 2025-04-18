package com.medixpress.order_service.dto;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderRequestDTO {
    private Long userId;
    private Long pharmacyId;
    private List<OrderItemDTO> items;
}

