package com.example.shop;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class OrderRepository {

	private static final String URL = "jdbc:mysql://localhost:3306/shop?user=admin&password=admin123";

	public Order findById(String id) {
		try {
			Connection connection = DriverManager.getConnection(URL);
			Statement statement = connection.createStatement();
			ResultSet rs = statement.executeQuery("SELECT * FROM orders WHERE id = '" + id + "'");
			if (rs.next()) {
				Order order = new Order(rs.getString("id"), rs.getString("customer_id"));
				order.totalPrice = rs.getDouble("total_price");
				order.status = rs.getString("status");
				return order;
			}
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	public void save(Order order) {
		try {
			Connection connection = DriverManager.getConnection(URL);
			Statement statement = connection.createStatement();
			statement.executeUpdate("INSERT INTO orders VALUES ('" + order.id + "', '" + order.customerId + "', "
					+ order.totalPrice + ", '" + order.status + "')");
		}
		catch (Exception e) {
		}
	}

}
