/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */

package com.uniwa.ticketbook;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class TicketBook {

    private JFrame frame;
    private CardLayout cardLayout;
    private JPanel mainPanel;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new TicketBook().startApp());
    }

    public void startApp() {
        frame = new JFrame("TicketBook");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(400, 250);

        cardLayout = new CardLayout();
        mainPanel = new JPanel(cardLayout);

        mainPanel.add(buildHomePage(), "home");
        mainPanel.add(buildRegisterPage(), "register");
        mainPanel.add(buildLoginPage(), "login");

        frame.getContentPane().add(mainPanel);
        frame.setVisible(true);
    }

    private JPanel buildHomePage() {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Welcome to TicketBook", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 22));

        JButton registerBtn = new JButton("Register");
        JButton loginBtn = new JButton("Login");

        registerBtn.addActionListener(e -> cardLayout.show(mainPanel, "register"));
        loginBtn.addActionListener(e -> cardLayout.show(mainPanel, "login"));

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(registerBtn);
        buttonPanel.add(loginBtn);

        panel.add(title, BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildRegisterPage() {
        JPanel panel = new JPanel(new GridLayout(5, 1, 10, 10));
        JTextField usernameField = new JTextField();
        JPasswordField passwordField = new JPasswordField();
        JButton registerBtn = new JButton("Register");

        panel.setBorder(BorderFactory.createEmptyBorder(20, 50, 20, 50));

        panel.add(new JLabel("Username:"));
        panel.add(usernameField);
        panel.add(new JLabel("Password:"));
        panel.add(passwordField);
        panel.add(registerBtn);

        registerBtn.addActionListener(e -> {
            String username = usernameField.getText();
            String password = new String(passwordField.getPassword());
            System.out.println("TicketBook Registration: " + username + " | " + password);
            JOptionPane.showMessageDialog(frame, "Registered! Returning to home.");
            cardLayout.show(mainPanel, "home");
        });

        return panel;
    }

    private JPanel buildLoginPage() {
        JPanel panel = new JPanel(new GridLayout(6, 1, 10, 10));
        JTextField emailField = new JTextField();
        JPasswordField passwordField = new JPasswordField();
        JButton loginBtn = new JButton("Login");
        JLabel messageLabel = new JLabel("", SwingConstants.CENTER);

        panel.setBorder(BorderFactory.createEmptyBorder(20, 50, 20, 50));

        panel.add(new JLabel("Email:"));
        panel.add(emailField);
        panel.add(new JLabel("Password:"));
        panel.add(passwordField);
        panel.add(loginBtn);
        panel.add(messageLabel);

        loginBtn.addActionListener(e -> {
            String email = emailField.getText();
            String password = new String(passwordField.getPassword());

            if (email.equals("123@example.com") && password.equals("1234")) {
                mainPanel.add(buildWelcomePage(email), "welcome");
                cardLayout.show(mainPanel, "welcome");
            } else {
                messageLabel.setText("❌ Invalid credentials.");
            }
        });

        return panel;
    }

    private JPanel buildWelcomePage(String email) {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel label = new JLabel("Welcome to TicketBook, " + email + "!", SwingConstants.CENTER);
        label.setFont(new Font("Arial", Font.BOLD, 20));
        panel.add(label, BorderLayout.CENTER);

        JButton backBtn = new JButton("Logout");
        backBtn.addActionListener(e -> cardLayout.show(mainPanel, "home"));
        panel.add(backBtn, BorderLayout.SOUTH);

        return panel;
    }
}