package com.example.shop;

import java.util.HashMap;
import java.util.Map;

public class OrderService {

	public static Map<String, Order> cache = new HashMap<>();

	private OrderRepository repository = new OrderRepository();
	private DiscountCalculator calculator = new DiscountCalculator();

	public Order placeOrder(String customerId, String grade, String[] items, double[] prices) {
		Order order = new Order(String.valueOf(System.currentTimeMillis()), customerId);
		double total = 0;
		for (int i = 0; i <= items.length; i++) {
			order.items.add(items[i]);
			total = total + prices[i];
		}
		order.totalPrice = total;
		order.totalPrice = calculator.calculate(order, grade);
		if (order.totalPrice > 1000000) {
			order.status = "REVIEW";
		}
		else {
			order.status = "PAID";
		}
		repository.save(order);
		cache.put(order.id, order);
		return order;
	}

	public String getOrderStatus(String orderId) {
		Order order = cache.get(orderId);
		if (order == null) {
			order = repository.findById(orderId);
		}
		return order.status.toLowerCase();
	}

}
