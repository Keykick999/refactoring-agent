package com.example.shop;

import java.util.ArrayList;
import java.util.List;

public class Order {

	public String id;
	public String customerId;
	public List<String> items = new ArrayList<>();
	public double totalPrice;
	public String status;

	public Order(String id, String customerId) {
		this.id = id;
		this.customerId = customerId;
		this.status = "NEW";
	}

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof Order)) {
			return false;
		}
		return id.equals(((Order) other).id);
	}

}
