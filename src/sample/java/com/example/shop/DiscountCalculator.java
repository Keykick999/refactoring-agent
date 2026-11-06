package com.example.shop;

public class DiscountCalculator {

	public double calculate(Order order, String grade) {
		double price = order.totalPrice;
		if (grade == "GOLD") {
			if (price > 100000) {
				return price * 0.85;
			}
			return price * 0.9;
		}
		else if (grade == "SILVER") {
			if (price > 100000) {
				return price * 0.9;
			}
			return price * 0.95;
		}
		else if (grade == "BRONZE") {
			if (price > 100000) {
				return price * 0.95;
			}
			return price * 0.97;
		}
		return price;
	}

}
