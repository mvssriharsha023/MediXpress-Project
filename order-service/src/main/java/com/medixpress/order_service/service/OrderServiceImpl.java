package com.medixpress.order_service.service;

import com.medixpress.order_service.OrderServiceApplication;
import com.medixpress.order_service.dto.OrderItemDTO;
import com.medixpress.order_service.exception.*;
import com.medixpress.order_service.model.Order;
import com.medixpress.order_service.model.OrderItem;
import com.medixpress.order_service.model.OrderStatus;
import com.medixpress.order_service.dto.OrderResponseDTO;
import com.medixpress.order_service.repository.OrderItemRepository;
import com.medixpress.order_service.repository.OrderRepository;
import com.medixpress.order_service.response.CartItemDTO;
import com.medixpress.order_service.response.MedicineResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements OrderService {
    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    @Autowired
    private RestTemplate restTemplate;

    public MedicineResponse getMedicineDetails(String medicineId) {
        String url = "http://medicine-service/api/medicines/" + medicineId;

        ResponseEntity<MedicineResponse> response = restTemplate.getForEntity(
                url,
                MedicineResponse.class
        );

        return Optional.ofNullable(response.getBody())
                .orElseThrow(() -> new RuntimeException("Medicine not found with id: " + medicineId));
    }

    public List<CartItemDTO> getUserCart(Long id) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("id", String.valueOf(id));

        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        ParameterizedTypeReference<List<CartItemDTO>> responseType =
                new ParameterizedTypeReference<>() {
                };

        ResponseEntity<List<CartItemDTO>> response = restTemplate.exchange(
                "http://cart-service/api/cart/user",
                HttpMethod.GET,
                requestEntity,
                responseType
        );

        return Optional.ofNullable(response.getBody())
                .orElse(Collections.emptyList());
    }

    public void clearUserCart(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("id", String.valueOf(userId));

        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "http://cart-service/api/cart/clear", // Use Eureka service name if using discovery
                HttpMethod.DELETE,
                requestEntity,
                String.class
        );

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to clear cart: " + response.getBody());
        }
    }

    public MedicineResponse reduceMedicineStock(String medicineId, int quantityToReduce) {
        String url = "http://medicine-service/api/medicines/reduce/" + medicineId;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Integer> requestEntity = new HttpEntity<>(quantityToReduce, headers);

        try {
            ResponseEntity<MedicineResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.PUT,
                    requestEntity,
                    MedicineResponse.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                return response.getBody();
            } else {
                throw new RuntimeException("Failed to reduce medicine stock: " + response.getStatusCode());
            }
        } catch (Exception ex) {
            throw new RuntimeException("Error while reducing medicine stock: " + ex.getMessage(), ex);
        }
    }




    @Override
    @Transactional
    public Order placeOrder(Long id) {
        log.info("Fetching cart for user ID: {}", id);

        List<CartItemDTO> cartItems = getUserCart(id);
        log.info("Cart fetched. Items count: {}", cartItems.size());

        System.out.println("Cart fetched");
        if (cartItems.isEmpty()) {
            throw new CartEmptyException("Cart is empty");
        }

        // Create order
        Order order = Order.builder()
                .userId(id)
                .pharmacyId(cartItems.getFirst().getPharmacyId()) // Assuming all items are from one pharmacy
                .orderDateTime(LocalDateTime.now())
                .totalAmount(0.0) // will update later
                .build();

        order = orderRepository.save(order);

        double total = 0.0;

        List<OrderItem> orderItems = new ArrayList<>();

        for (CartItemDTO item : cartItems) {
            System.out.println("Processing medicine: " + item.getMedicineId() + ", qty: " + item.getQuantity());

            MedicineResponse med = getMedicineDetails(item.getMedicineId());

            if (med.getQuantity() < item.getQuantity()) {
                throw new OutOfStockException("Medicine " + med.getName() + " is out of stock.");
            }

            double price = med.getPrice() * item.getQuantity();

            OrderItem orderItem = OrderItem.builder()
                    .orderId(order.getId())
                    .medicineId(item.getMedicineId())
                    .pricePerUnit(med.getPrice())
                    .quantity(item.getQuantity())
                    .totalPrice(price)
                    .build();

            orderItemRepository.save(orderItem);
            orderItems.add(orderItem);

            // Update stock
//            med.setQuantity(med.getQuantity() - item.getQuantity());
            reduceMedicineStock(med.getId(), item.getQuantity());

            total += price;
        }

        order.setItems(orderItems);
        order.setTotalAmount(total);
        order.setStatus(OrderStatus.PLACED);
        orderRepository.save(order);

        // Clear the cart
        clearUserCart(id);
        return order;
    }

    @Override
    public List<OrderResponseDTO> getOrdersByUser(Long userId) {
        return orderRepository.findByUserId(userId).stream()
                .map(order -> {
                    List<OrderItemDTO> items = orderItemRepository.findByOrderId(order.getId())
                            .stream()
                            .map(item -> OrderItemDTO.builder()
                                    .id(item.getId())
                                    .medicineId(item.getMedicineId())
                                    .quantity(item.getQuantity())
                                    .pricePerUnit(item.getPricePerUnit())
                                    .totalPrice(item.getTotalPrice())
                                    .orderId(order.getId())
                                    .build())
                            .collect(Collectors.toList());

                    return OrderResponseDTO.builder()
                            .id(order.getId())
                            .userId(order.getUserId())
                            .pharmacyId(order.getPharmacyId())
                            .status(order.getStatus())
                            .orderDateTime(order.getOrderDateTime())
                            .totalAmount(order.getTotalAmount())
                            .items(items)
                            .build();
                })
                .collect(Collectors.toList());

    }

    @Override
    public List<OrderResponseDTO> getOrdersByPharmacy(Long pharmacyId) {
        return orderRepository.findByPharmacyId(pharmacyId).stream()
                .map(order -> {
                    List<OrderItemDTO> items = orderItemRepository.findByOrderId(order.getId())
                            .stream()
                            .map(item -> OrderItemDTO.builder()
                                    .id(item.getId())
                                    .medicineId(item.getMedicineId())
                                    .quantity(item.getQuantity())
                                    .pricePerUnit(item.getPricePerUnit())
                                    .totalPrice(item.getTotalPrice())
                                    .orderId(item.getOrderId())
                                    .build())
                            .collect(Collectors.toList());

                    return OrderResponseDTO.builder()
                            .id(order.getId())
                            .userId(order.getUserId())
                            .pharmacyId(order.getPharmacyId())
                            .status(order.getStatus())
                            .orderDateTime(order.getOrderDateTime())
                            .totalAmount(order.getTotalAmount())
                            .items(items)
                            .build();
                })
                .collect(Collectors.toList());

    }

    @Override
    public OrderResponseDTO getOrderDetails(String orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found123"));

        List<OrderItemDTO> items = orderItemRepository.findByOrderId(orderId)
                .stream()
                .map(item -> OrderItemDTO.builder()
                        .medicineId(item.getMedicineId())
                        .pricePerUnit(item.getPricePerUnit())
                        .quantity(item.getQuantity())
                        .totalPrice(item.getTotalPrice())
                        .build())
                .collect(Collectors.toList());

        return OrderResponseDTO.builder()
                .id(order.getId())
                .userId(order.getUserId())
                .pharmacyId(order.getPharmacyId())
                .orderDateTime(order.getOrderDateTime())
                .totalAmount(order.getTotalAmount())
                .items(items)
                .status(order.getStatus())
                .build();
    }

    public Order updateStatusByUser(Long userId, String orderId, OrderStatus status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not present"));

        if (status.toString().equals("CANCELLED")) {
            if (order.getStatus().toString().equals("PLACED")) {
                order.setStatus(OrderStatus.CANCELLED);
            } else {
                throw new OutForDeliveryException("This order is already out for delivery or delivered");
            }

        } else if (status.toString().equals("DELIVERED")) {
            order.setStatus(OrderStatus.DELIVERED);
        } else {
            throw new UnauthorizedAccessException("Unauthorized access on this order");
        }
        orderRepository.save(order);
        return order;
    }

    @Override
    public Order updateStatusByPharmacy(Long pharmacyId, String orderId, OrderStatus status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not present"));

        if (status.toString().equals("OUT_OF_DELIVERY") && order.getStatus().toString().equals("PLACED")) {
            order.setStatus(OrderStatus.OUT_OF_DELIVERY);
        } else {
            throw new UnauthorizedAccessException("Unauthorized access on this order");
        }
        orderRepository.save(order);
        return order;
    }


}
