package com.uniwa.ticketbook;

import io.github.cdimascio.dotenv.Dotenv;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.*;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Base64;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.DefaultCellEditor;
import java.awt.image.BufferedImage;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import javax.swing.JFileChooser;
// For TOTP functionality
import org.apache.commons.codec.binary.Base32;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Enhanced TicketBook with all new features: - 2FA Authentication - Favorites
 * System - Booking Cancellation - User Management - Support Tickets - Refund
 * System - Admin Invitation System
 */
public class TicketBook {

    private JFrame frame;
    private CardLayout cardLayout;
    private JPanel mainPanel;
    private int currentUserId = -1;
    private String currentUsername;
    private String currentUserRole;
    private JPanel cachedMoviesPanel = null;
    private JPanel cachedShowtimesPanel = null;
    private int lastMovieId = -1;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new TicketBook().startApp());
    }

    private void startApp() {
        // Initialize database tables
        initializeDatabase();

        frame = new JFrame("TicketBook - Enhanced Edition");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(900, 700);
        frame.setLocationRelativeTo(null);

        cardLayout = new CardLayout();
        mainPanel = new JPanel(cardLayout);

        // Pre-Login panels
        mainPanel.add(buildHomePage(), "home");
        mainPanel.add(buildRegisterPage(), "register");
        mainPanel.add(buildLoginPage(), "login");

        frame.getContentPane().add(mainPanel);
        cardLayout.show(mainPanel, "home");
        frame.setVisible(true);
        // ΟΧΙ preload, ΟΧΙ popup
    }

    /**
     * Initialize database with new tables and columns for enhanced features
     */
    private void initializeDatabase() {
        try (Connection c = createConnection()) {
            Statement stmt = c.createStatement();

            // Add new columns to users table if they don't exist
            try {
                stmt.execute("ALTER TABLE users ADD COLUMN totp_secret VARCHAR(32)");
            } catch (SQLException e) {
                /* Column might already exist */ }

            try {
                stmt.execute("ALTER TABLE users ADD COLUMN is_active BOOLEAN DEFAULT TRUE");
            } catch (SQLException e) {
                /* Column might already exist */ }

            // Create admin_invitations table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS admin_invitations (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    invitation_key VARCHAR(64) UNIQUE NOT NULL,
                    created_by INT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    expires_at TIMESTAMP,
                    used_by INT,
                    used_at TIMESTAMP,
                    FOREIGN KEY (created_by) REFERENCES users(id),
                    FOREIGN KEY (used_by) REFERENCES users(id)
                )
            """);

            // Create favorites table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS favorites (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    user_id INT NOT NULL,
                    movie_id INT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY unique_favorite (user_id, movie_id),
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    FOREIGN KEY (movie_id) REFERENCES movies(id) ON DELETE CASCADE
                )
            """);

            // Create support tickets tables
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS support_tickets (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    user_id INT NOT NULL,
                    subject VARCHAR(200) NOT NULL,
                    status ENUM('open', 'in_progress', 'closed') DEFAULT 'open',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    FOREIGN KEY (user_id) REFERENCES users(id)
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ticket_messages (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    ticket_id INT NOT NULL,
                    sender_id INT NOT NULL,
                    message TEXT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (ticket_id) REFERENCES support_tickets(id) ON DELETE CASCADE,
                    FOREIGN KEY (sender_id) REFERENCES users(id)
                )
            """);

            // Create payments and refunds tables
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS payments (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    booking_id INT NOT NULL,
                    amount DECIMAL(10,2) NOT NULL,
                    payment_method VARCHAR(50),
                    status ENUM('pending', 'completed', 'failed', 'refunded') DEFAULT 'pending',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (booking_id) REFERENCES bookings(id)
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS refunds (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    payment_id INT NOT NULL,
                    amount DECIMAL(10,2) NOT NULL,
                    reason TEXT,
                    status ENUM('pending', 'approved', 'rejected', 'completed') DEFAULT 'pending',
                    requested_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    processed_at TIMESTAMP,
                    processed_by INT,
                    FOREIGN KEY (payment_id) REFERENCES payments(id),
                    FOREIGN KEY (processed_by) REFERENCES users(id)
                )
            """);

            // Add canceled_at column to bookings if it doesn't exist
            try {
                stmt.execute("ALTER TABLE bookings ADD COLUMN canceled_at TIMESTAMP");
            } catch (SQLException e) {
                /* Column might already exist */ }

        } catch (SQLException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Database initialization error: " + e.getMessage());
        }
    }

    // ===============================
    // HOME PAGE
    // ===============================
    private JPanel buildHomePage() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(240, 240, 245));

        JLabel title = new JLabel("Καλωσορίσατε στο TicketBook", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 28));
        title.setBorder(BorderFactory.createEmptyBorder(50, 0, 30, 0));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10));
        buttons.setOpaque(false);

        JButton btnRegister = new JButton("Εγγραφή");
        JButton btnLogin = new JButton("Είσοδος");

        btnRegister.setPreferredSize(new Dimension(120, 40));
        btnLogin.setPreferredSize(new Dimension(120, 40));

        btnRegister.addActionListener(e -> cardLayout.show(mainPanel, "register"));
        btnLogin.addActionListener(e -> cardLayout.show(mainPanel, "login"));

        buttons.add(btnRegister);
        buttons.add(btnLogin);

        panel.add(title, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);

        return panel;
    }

    // ===============================
    // REGISTRATION PAGE WITH ADMIN INVITATION
    // ===============================
    private JPanel buildRegisterPage() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(20, 50, 20, 50));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        JTextField usernameField = new JTextField(20);
        JTextField emailField = new JTextField(20);
        JPasswordField pwdField = new JPasswordField(20);
        JTextField invitationField = new JTextField(20);
        JCheckBox enable2FA = new JCheckBox("Ενεργοποίηση 2FA (Two-Factor Authentication)");

        int row = 0;

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        JLabel titleLabel = new JLabel("Εγγραφή Νέου Χρήστη", SwingConstants.CENTER);
        titleLabel.setFont(new Font("Arial", Font.BOLD, 20));
        panel.add(titleLabel, gbc);

        gbc.gridwidth = 1;
        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(new JLabel("Όνομα Χρήστη:"), gbc);
        gbc.gridx = 1;
        gbc.gridy = row++;
        panel.add(usernameField, gbc);

        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(new JLabel("Email:"), gbc);
        gbc.gridx = 1;
        gbc.gridy = row++;
        panel.add(emailField, gbc);

        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(new JLabel("Κωδικός:"), gbc);
        gbc.gridx = 1;
        gbc.gridy = row++;
        panel.add(pwdField, gbc);

        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(new JLabel("Admin Key (προαιρετικό):"), gbc);
        gbc.gridx = 1;
        gbc.gridy = row++;
        panel.add(invitationField, gbc);

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        panel.add(enable2FA, gbc);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton btnRegister = new JButton("Εγγραφή");
        JButton btnBack = new JButton("Πίσω");

        btnRegister.addActionListener(e -> {
            String username = usernameField.getText().trim();
            String email = emailField.getText().trim();
            String password = new String(pwdField.getPassword()).trim();
            String invitationKey = invitationField.getText().trim();

            if (username.isEmpty() || email.isEmpty() || password.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Παρακαλώ συμπληρώστε όλα τα απαιτούμενα πεδία");
                return;
            }

            registerUser(username, email, password, invitationKey, enable2FA.isSelected());
        });

        btnBack.addActionListener(e -> cardLayout.show(mainPanel, "home"));

        buttonPanel.add(btnBack);
        buttonPanel.add(btnRegister);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        panel.add(buttonPanel, gbc);

        return panel;
    }

    // ===============================
    // LOGIN PAGE WITH 2FA
    // ===============================
    private JPanel buildLoginPage() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(20, 50, 20, 50));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(10, 10, 10, 10);

        JTextField ueField = new JTextField(20);
        JPasswordField pwField = new JPasswordField(20);
        JLabel msgLabel = new JLabel("", SwingConstants.CENTER);
        msgLabel.setForeground(Color.RED);

        int row = 0;

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        JLabel titleLabel = new JLabel("Είσοδος Χρήστη", SwingConstants.CENTER);
        titleLabel.setFont(new Font("Arial", Font.BOLD, 20));
        panel.add(titleLabel, gbc);

        gbc.gridwidth = 1;
        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(new JLabel("Username/Email:"), gbc);
        gbc.gridx = 1;
        gbc.gridy = row++;
        panel.add(ueField, gbc);

        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(new JLabel("Κωδικός:"), gbc);
        gbc.gridx = 1;
        gbc.gridy = row++;
        panel.add(pwField, gbc);

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        panel.add(msgLabel, gbc);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton btnLogin = new JButton("Είσοδος");
        JButton btnBack = new JButton("Πίσω");

        Runnable loginAction = () -> {
            String ue = ueField.getText().trim();
            String pw = new String(pwField.getPassword()).trim();

            if (ue.isEmpty() || pw.isEmpty()) {
                msgLabel.setText("Συμπληρώστε όλα τα πεδία");
                return;
            }

            performLogin(ue, pw, msgLabel);
        };

        btnLogin.addActionListener(e -> loginAction.run());
        btnBack.addActionListener(e -> cardLayout.show(mainPanel, "home"));

        // Add Enter key listeners
        ueField.addActionListener(e -> loginAction.run());
        pwField.addActionListener(e -> loginAction.run());

        buttonPanel.add(btnBack);
        buttonPanel.add(btnLogin);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        panel.add(buttonPanel, gbc);

        return panel;
    }

    // ===============================
    // USER POST-LOGIN HOME
    // ===============================
    private JPanel buildPostLoginHome() {
        JPanel panel = new JPanel(new BorderLayout());

        JLabel title = new JLabel("Καλώς ήρθες, " + currentUsername + "!", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 24));
        title.setBorder(BorderFactory.createEmptyBorder(30, 0, 30, 0));

        JPanel buttons = new JPanel(new GridLayout(2, 2, 20, 20));
        buttons.setBorder(BorderFactory.createEmptyBorder(20, 50, 20, 50));

        JButton btnMovies = new JButton("Αναζήτηση Ταινιών");
        JButton btnMyBookings = new JButton("Οι Κρατήσεις μου");
        JButton btnFavorites = new JButton("Αγαπημένες Ταινίες");
        JButton btnSupport = new JButton("Υποστήριξη");

        btnMovies.addActionListener(e -> {
            // Χρήση cached panel αν υπάρχει
            if (cachedMoviesPanel == null) {
                cachedMoviesPanel = buildMoviesPage();
                mainPanel.add(cachedMoviesPanel, "movies");
            }
            cardLayout.show(mainPanel, "movies");
        });

        btnMovies.addActionListener(e -> {
            JPanel moviesPanel = buildMoviesPage();
            mainPanel.add(moviesPanel, "movies");
            cardLayout.show(mainPanel, "movies");
        });

        btnMyBookings.addActionListener(e -> {
            JPanel bookingsPanel = buildMyBookingsPage();
            mainPanel.add(bookingsPanel, "myBookings");
            cardLayout.show(mainPanel, "myBookings");
        });

        btnFavorites.addActionListener(e -> {
            JPanel favPanel = buildFavoritesPage();
            mainPanel.add(favPanel, "favorites");
            cardLayout.show(mainPanel, "favorites");
        });

        btnSupport.addActionListener(e -> {
            JPanel supportPanel = buildSupportPage();
            mainPanel.add(supportPanel, "support");
            cardLayout.show(mainPanel, "support");
        });

        buttons.add(btnMovies);
        buttons.add(btnMyBookings);
        buttons.add(btnFavorites);
        buttons.add(btnSupport);

        JPanel bottomPanel = new JPanel();
        JButton btnLogout = new JButton("Αποσύνδεση");
        btnLogout.addActionListener(e -> logout());
        bottomPanel.add(btnLogout);

        panel.add(title, BorderLayout.NORTH);
        panel.add(buttons, BorderLayout.CENTER);
        panel.add(bottomPanel, BorderLayout.SOUTH);

        return panel;
    }

    // ===============================
    // ADMIN POST-LOGIN HOME
    // ===============================
    private JPanel buildAdminHome() {
        JPanel panel = new JPanel(new BorderLayout());

        JLabel title = new JLabel("Πίνακας Διαχειριστή - " + currentUsername, SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 24));
        title.setBorder(BorderFactory.createEmptyBorder(30, 0, 30, 0));

        JPanel buttons = new JPanel(new GridLayout(3, 2, 20, 20));
        buttons.setBorder(BorderFactory.createEmptyBorder(20, 50, 20, 50));

        JButton btnMovies = new JButton("Διαχείριση Ταινιών");
        JButton btnBookings = new JButton("Διαχείριση Κρατήσεων");
        JButton btnUsers = new JButton("Διαχείριση Χρηστών");
        JButton btnInvitations = new JButton("Προσκλήσεις Admin");
        JButton btnSupport = new JButton("Tickets Υποστήριξης");
        JButton btnRefunds = new JButton("Αιτήματα Επιστροφών");

        btnMovies.addActionListener(e -> {
            JPanel panel2 = buildAdminMoviesPage();
            mainPanel.add(panel2, "adminMovies");
            cardLayout.show(mainPanel, "adminMovies");
        });

        btnBookings.addActionListener(e -> {
            JPanel panel2 = buildAdminBookingsPage();
            mainPanel.add(panel2, "adminBookings");
            cardLayout.show(mainPanel, "adminBookings");
        });

        btnUsers.addActionListener(e -> {
            JPanel panel2 = buildUsersManagementPage();
            mainPanel.add(panel2, "usersManagement");
            cardLayout.show(mainPanel, "usersManagement");
        });

        btnInvitations.addActionListener(e -> {
            JPanel panel2 = buildAdminInvitationsPage();
            mainPanel.add(panel2, "adminInvitations");
            cardLayout.show(mainPanel, "adminInvitations");
        });

        btnSupport.addActionListener(e -> {
            JPanel panel2 = buildAdminSupportPage();
            mainPanel.add(panel2, "adminSupport");
            cardLayout.show(mainPanel, "adminSupport");
        });

        btnRefunds.addActionListener(e -> {
            JPanel panel2 = buildRefundsManagementPage();
            mainPanel.add(panel2, "refundsManagement");
            cardLayout.show(mainPanel, "refundsManagement");
        });

        buttons.add(btnMovies);
        buttons.add(btnBookings);
        buttons.add(btnUsers);
        buttons.add(btnInvitations);
        buttons.add(btnSupport);
        buttons.add(btnRefunds);

        JPanel bottomPanel = new JPanel();
        JButton btnLogout = new JButton("Αποσύνδεση");
        btnLogout.addActionListener(e -> logout());
        bottomPanel.add(btnLogout);

        panel.add(title, BorderLayout.NORTH);
        panel.add(buttons, BorderLayout.CENTER);
        panel.add(bottomPanel, BorderLayout.SOUTH);

        return panel;
    }

    // CONTINUATION OF TicketBook.java
// Add these methods to the TicketBook class
    // ===============================
    // MOVIES PAGE WITH FAVORITES
    // ===============================
    private JPanel buildMoviesPage() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel welcome = new JLabel("Αναζήτηση Ταινιών", SwingConstants.CENTER);
        welcome.setFont(new Font("Arial", Font.BOLD, 20));

        JTextField searchField = new JTextField();
        searchField.setFont(new Font("Arial", Font.PLAIN, 14));

        DefaultListModel<MovieItem> model = new DefaultListModel<>();
        JList<MovieItem> list = new JList<>(model);
        list.setCellRenderer(new MovieCellRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        loadMoviesWithFavorites(model, "");

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                update();
            }

            public void removeUpdate(DocumentEvent e) {
                update();
            }

            public void changedUpdate(DocumentEvent e) {
                update();
            }

            private void update() {
                loadMoviesWithFavorites(model, searchField.getText().trim());
            }
        });

        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        topPanel.add(welcome, BorderLayout.NORTH);
        topPanel.add(new JLabel("Αναζήτηση:"), BorderLayout.WEST);
        topPanel.add(searchField, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton btnBack = new JButton("Πίσω");
        JButton btnFavorite = new JButton("Προσθήκη/Αφαίρεση Αγαπημένης");
        JButton btnShowtimes = new JButton("Προβολές");

        btnBack.addActionListener(e -> cardLayout.show(mainPanel, "postLoginHome"));

        btnFavorite.addActionListener(e -> {
            MovieItem selected = list.getSelectedValue();
            if (selected != null) {
                toggleFavorite(selected.getId());
                loadMoviesWithFavorites(model, searchField.getText().trim());
            } else {
                JOptionPane.showMessageDialog(frame, "Επιλέξτε μια ταινία");
            }
        });

        btnShowtimes.addActionListener(e -> {
            MovieItem selected = list.getSelectedValue();
            if (selected != null) {
                JPanel showtimesPanel = buildShowtimesPage(selected.getId());
                mainPanel.add(showtimesPanel, "showtimes");
                cardLayout.show(mainPanel, "showtimes");
            } else {
                JOptionPane.showMessageDialog(frame, "Επιλέξτε μια ταινία");
            }
        });

        buttonPanel.add(btnBack);
        buttonPanel.add(btnFavorite);
        buttonPanel.add(btnShowtimes);

        panel.add(topPanel, BorderLayout.NORTH);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    // ===============================
    // FAVORITES PAGE
    // ===============================
    private JPanel buildFavoritesPage() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel title = new JLabel("Οι Αγαπημένες μου Ταινίες", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 20));

        DefaultListModel<MovieItem> model = new DefaultListModel<>();
        JList<MovieItem> list = new JList<>(model);
        list.setCellRenderer(new MovieCellRenderer());

        loadFavoriteMovies(model);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton btnBack = new JButton("Πίσω");
        JButton btnRemove = new JButton("Αφαίρεση από Αγαπημένες");
        JButton btnShowtimes = new JButton("Προβολές");

        btnBack.addActionListener(e -> cardLayout.show(mainPanel, "postLoginHome"));

        btnRemove.addActionListener(e -> {
            MovieItem selected = list.getSelectedValue();
            if (selected != null) {
                toggleFavorite(selected.getId());
                loadFavoriteMovies(model);
            }
        });

        btnShowtimes.addActionListener(e -> {
            MovieItem selected = list.getSelectedValue();
            if (selected != null) {
                JPanel showtimesPanel = buildShowtimesPage(selected.getId());
                mainPanel.add(showtimesPanel, "showtimes");
                cardLayout.show(mainPanel, "showtimes");
            }
        });

        buttonPanel.add(btnBack);
        buttonPanel.add(btnRemove);
        buttonPanel.add(btnShowtimes);

        panel.add(title, BorderLayout.NORTH);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    // ===============================
    // MY BOOKINGS PAGE WITH CANCEL
    // ===============================
    private JPanel buildMyBookingsPage() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel title = new JLabel("Οι Κρατήσεις μου", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 20));

        DefaultListModel<String> model = new DefaultListModel<>();
        JList<String> list = new JList<>(model);
        List<Integer> bookingIds = new ArrayList<>();
        List<LocalDateTime> showtimes = new ArrayList<>();
        List<String> statuses = new ArrayList<>();

        loadUserBookings(model, bookingIds, showtimes, statuses);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton btnBack = new JButton("Πίσω");
        JButton btnCancel = new JButton("Ακύρωση Κράτησης");
        JButton btnQR = new JButton("Προβολή QR");
        JButton btnRefund = new JButton("Αίτημα Επιστροφής");

        btnBack.addActionListener(e -> cardLayout.show(mainPanel, "postLoginHome"));

        btnCancel.addActionListener(e -> {
            int idx = list.getSelectedIndex();
            if (idx >= 0 && idx < bookingIds.size()) {
                if (statuses.get(idx).equals("canceled")) {
                    JOptionPane.showMessageDialog(frame, "Η κράτηση έχει ήδη ακυρωθεί");
                    return;
                }
                LocalDateTime showtime = showtimes.get(idx);
                if (showtime.minusHours(24).isAfter(LocalDateTime.now())) {
                    cancelBooking(bookingIds.get(idx));
                    loadUserBookings(model, bookingIds, showtimes, statuses);
                } else {
                    JOptionPane.showMessageDialog(frame,
                            "Η ακύρωση επιτρέπεται μόνο 24 ώρες πριν την προβολή");
                }
            } else {
                JOptionPane.showMessageDialog(frame, "Επιλέξτε μια κράτηση");
            }
        });

        btnQR.addActionListener(e -> {
            int idx = list.getSelectedIndex();
            if (idx >= 0 && idx < bookingIds.size()) {
                if (!statuses.get(idx).equals("canceled")) {
                    showBookingQR(bookingIds.get(idx));
                } else {
                    JOptionPane.showMessageDialog(frame, "Η κράτηση έχει ακυρωθεί");
                }
            }
        });

        btnRefund.addActionListener(e -> {
            int idx = list.getSelectedIndex();
            if (idx >= 0 && idx < bookingIds.size()) {
                requestRefund(bookingIds.get(idx));
            }
        });

        buttonPanel.add(btnBack);
        buttonPanel.add(btnCancel);
        buttonPanel.add(btnQR);
        buttonPanel.add(btnRefund);

        panel.add(title, BorderLayout.NORTH);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    // ===============================
    // SUPPORT PAGE
    // ===============================
    private JPanel buildSupportPage() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel title = new JLabel("Υποστήριξη Πελατών", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 20));

        DefaultListModel<String> model = new DefaultListModel<>();
        JList<String> list = new JList<>(model);
        List<Integer> ticketIds = new ArrayList<>();

        loadUserTickets(model, ticketIds);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton btnBack = new JButton("Πίσω");
        JButton btnNew = new JButton("Νέο Αίτημα");
        JButton btnView = new JButton("Προβολή Αιτήματος");

        btnBack.addActionListener(e -> cardLayout.show(mainPanel, "postLoginHome"));

        btnNew.addActionListener(e -> {
            showNewTicketDialog();
            loadUserTickets(model, ticketIds);
        });

        btnView.addActionListener(e -> {
            int idx = list.getSelectedIndex();
            if (idx >= 0 && idx < ticketIds.size()) {
                showTicketMessagesDialog(ticketIds.get(idx));
                loadUserTickets(model, ticketIds);
            }
        });

        buttonPanel.add(btnBack);
        buttonPanel.add(btnNew);
        buttonPanel.add(btnView);

        panel.add(title, BorderLayout.NORTH);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    // ===============================
    // ADMIN PANELS
    // ===============================
    private JPanel buildUsersManagementPage() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel title = new JLabel("Διαχείριση Χρηστών", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 20));

        String[] columns = {"ID", "Username", "Email", "Role", "2FA", "Active"};
        DefaultTableModel model = new DefaultTableModel(columns, 0);
        JTable table = new JTable(model);

        loadUsersData(model);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton btnBack = new JButton("Πίσω");
        JButton btnToggleActive = new JButton("Ενεργοποίηση/Απενεργοποίηση");
        JButton btnChangeRole = new JButton("Αλλαγή Ρόλου");
        JButton btnRefresh = new JButton("Ανανέωση");

        btnBack.addActionListener(e -> cardLayout.show(mainPanel, "AdminHome"));

        btnToggleActive.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                int userId = (int) model.getValueAt(row, 0);
                toggleUserActive(userId);
                loadUsersData(model);
            }
        });

        btnChangeRole.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                int userId = (int) model.getValueAt(row, 0);
                changeUserRole(userId);
                loadUsersData(model);
            }
        });

        btnRefresh.addActionListener(e -> loadUsersData(model));

        buttonPanel.add(btnBack);
        buttonPanel.add(btnToggleActive);
        buttonPanel.add(btnChangeRole);
        buttonPanel.add(btnRefresh);

        panel.add(title, BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    // Αντικαταστήστε το buildAdminInvitationsPage με αυτό:
    private JPanel buildAdminInvitationsPage() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel title = new JLabel("Διαχείριση Προσκλήσεων Admin", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 20));

        // Use JTable instead of JList for better control
        String[] columns = {"ID", "Κλειδί (Partial)", "Δημιουργός", "Κατάσταση", "Λήξη"};
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false; // Make table non-editable
            }
        };

        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(25);

        // Store full invitation data
        List<InvitationData> invitations = new ArrayList<>();

        loadInvitationsTable(model, invitations);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton btnBack = new JButton("Πίσω");
        JButton btnCreate = new JButton("Δημιουργία Πρόσκλησης");
        JButton btnViewKey = new JButton("Προβολή Κλειδιού");
        JButton btnDeactivate = new JButton("Απενεργοποίηση");
        JButton btnRefresh = new JButton("Ανανέωση");

        btnBack.addActionListener(e -> cardLayout.show(mainPanel, "AdminHome"));

        btnCreate.addActionListener(e -> {
            String key = generateInvitationKey();
            createAdminInvitation(key);
            loadInvitationsTable(model, invitations);

            // Show key in a copyable dialog
            showInvitationKeyDialog(key, true);
        });

        btnViewKey.addActionListener(e -> {
            int selectedRow = table.getSelectedRow();
            if (selectedRow >= 0 && selectedRow < invitations.size()) {
                InvitationData inv = invitations.get(selectedRow);
                if (inv.status.equals("Active")) {
                    showInvitationKeyDialog(inv.fullKey, false);
                } else {
                    JOptionPane.showMessageDialog(frame,
                            "Το κλειδί δεν είναι διαθέσιμο (" + inv.status + ")",
                            "Μη Διαθέσιμο",
                            JOptionPane.WARNING_MESSAGE);
                }
            } else {
                JOptionPane.showMessageDialog(frame, "Επιλέξτε μια πρόσκληση");
            }
        });

        btnDeactivate.addActionListener(e -> {
            int selectedRow = table.getSelectedRow();
            if (selectedRow >= 0 && selectedRow < invitations.size()) {
                InvitationData inv = invitations.get(selectedRow);
                if (inv.status.equals("Active")) {
                    int confirm = JOptionPane.showConfirmDialog(frame,
                            "Είστε σίγουροι ότι θέλετε να απενεργοποιήσετε αυτή την πρόσκληση;",
                            "Επιβεβαίωση",
                            JOptionPane.YES_NO_OPTION);

                    if (confirm == JOptionPane.YES_OPTION) {
                        deactivateInvitation(inv.id);
                        loadInvitationsTable(model, invitations);
                    }
                } else {
                    JOptionPane.showMessageDialog(frame,
                            "Η πρόσκληση δεν είναι ενεργή (" + inv.status + ")");
                }
            } else {
                JOptionPane.showMessageDialog(frame, "Επιλέξτε μια πρόσκληση");
            }
        });

        btnRefresh.addActionListener(e -> loadInvitationsTable(model, invitations));

        buttonPanel.add(btnBack);
        buttonPanel.add(btnCreate);
        buttonPanel.add(btnViewKey);
        buttonPanel.add(btnDeactivate);
        buttonPanel.add(btnRefresh);

        panel.add(title, BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

// New method to load invitations into table
    private void loadInvitationsTable(DefaultTableModel model, List<InvitationData> invitations) {
        model.setRowCount(0);
        invitations.clear();

        String sql = """
        SELECT ai.id, ai.invitation_key, ai.expires_at, ai.used_at,
               u1.username as created_by_name, 
               u2.username as used_by_name
        FROM admin_invitations ai
        LEFT JOIN users u1 ON ai.created_by = u1.id
        LEFT JOIN users u2 ON ai.used_by = u2.id
        ORDER BY ai.created_at DESC
    """;

        try (Connection c = createConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {

            while (rs.next()) {
                int id = rs.getInt("id");
                String key = rs.getString("invitation_key");
                String createdBy = rs.getString("created_by_name");
                String usedBy = rs.getString("used_by_name");
                Timestamp expires = rs.getTimestamp("expires_at");
                Timestamp usedAt = rs.getTimestamp("used_at");

                String status;
                if (usedBy != null) {
                    status = "Used";
                } else if (expires.before(new Timestamp(System.currentTimeMillis()))) {
                    status = "Expired";
                } else {
                    status = "Active";
                }

                // Store full data
                invitations.add(new InvitationData(id, key, status));

                // Show partial key in table (first and last 4 chars)
                String partialKey = key.length() > 10
                        ? key.substring(0, 4) + "..." + key.substring(key.length() - 4)
                        : key.substring(0, Math.min(8, key.length())) + "...";

                String expiresStr = expires.toLocalDateTime()
                        .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));

                if (usedAt != null) {
                    expiresStr = "Used: " + usedAt.toLocalDateTime()
                            .format(DateTimeFormatter.ofPattern("dd/MM"));
                }

                Object[] row = {id, partialKey, createdBy, status, expiresStr};
                model.addRow(row);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

// Show invitation key in copyable dialog
    private void showInvitationKeyDialog(String key, boolean isNew) {
        JDialog dialog = new JDialog(frame, isNew ? "Νέο Κλειδί Πρόσκλησης" : "Κλειδί Πρόσκλησης", true);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(500, 200);

        JTextArea keyArea = new JTextArea(3, 40);
        keyArea.setText(key);
        keyArea.setEditable(false);
        keyArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        keyArea.setLineWrap(true);
        keyArea.setWrapStyleWord(true);

        // Make it selectable for copying
        keyArea.setSelectionStart(0);
        keyArea.setSelectionEnd(key.length());

        JPanel topPanel = new JPanel();
        JLabel instructions = new JLabel(
                isNew ? "Αντιγράψτε και στείλτε αυτό το κλειδί (ισχύει για 7 ημέρες):"
                        : "Κλειδί πρόσκλησης:");
        topPanel.add(instructions);

        JPanel buttonPanel = new JPanel();
        JButton btnCopy = new JButton("Αντιγραφή");
        JButton btnClose = new JButton("Κλείσιμο");

        btnCopy.addActionListener(e -> {
            keyArea.selectAll();
            keyArea.copy();
            JOptionPane.showMessageDialog(dialog, "Το κλειδί αντιγράφηκε!");
        });

        btnClose.addActionListener(e -> dialog.dispose());

        buttonPanel.add(btnCopy);
        buttonPanel.add(btnClose);

        dialog.add(topPanel, BorderLayout.NORTH);
        dialog.add(new JScrollPane(keyArea), BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

// Deactivate invitation
    private void deactivateInvitation(int invitationId) {
        String sql = "UPDATE admin_invitations SET expires_at = NOW() WHERE id = ? AND used_by IS NULL";

        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setInt(1, invitationId);
            int affected = st.executeUpdate();

            if (affected > 0) {
                JOptionPane.showMessageDialog(frame, "Η πρόσκληση απενεργοποιήθηκε");
            } else {
                JOptionPane.showMessageDialog(frame, "Δεν ήταν δυνατή η απενεργοποίηση");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Σφάλμα: " + e.getMessage());
        }
    }

// Inner class for invitation data
    private static class InvitationData {

        final int id;
        final String fullKey;
        final String status;

        InvitationData(int id, String fullKey, String status) {
            this.id = id;
            this.fullKey = fullKey;
            this.status = status;
        }
    }

    private JPanel buildAdminSupportPage() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel title = new JLabel("Διαχείριση Αιτημάτων Υποστήριξης", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 20));

        DefaultListModel<String> model = new DefaultListModel<>();
        JList<String> list = new JList<>(model);
        List<Integer> ticketIds = new ArrayList<>();

        loadAllTickets(model, ticketIds);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton btnBack = new JButton("Πίσω");
        JButton btnView = new JButton("Προβολή/Απάντηση");
        JButton btnClose = new JButton("Κλείσιμο Ticket");
        JButton btnRefresh = new JButton("Ανανέωση");

        btnBack.addActionListener(e -> cardLayout.show(mainPanel, "AdminHome"));

        btnView.addActionListener(e -> {
            int idx = list.getSelectedIndex();
            if (idx >= 0 && idx < ticketIds.size()) {
                showTicketMessagesDialog(ticketIds.get(idx));
                loadAllTickets(model, ticketIds);
            }
        });

        btnClose.addActionListener(e -> {
            int idx = list.getSelectedIndex();
            if (idx >= 0 && idx < ticketIds.size()) {
                closeTicket(ticketIds.get(idx));
                loadAllTickets(model, ticketIds);
            }
        });

        btnRefresh.addActionListener(e -> loadAllTickets(model, ticketIds));

        buttonPanel.add(btnBack);
        buttonPanel.add(btnView);
        buttonPanel.add(btnClose);
        buttonPanel.add(btnRefresh);

        panel.add(title, BorderLayout.NORTH);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildRefundsManagementPage() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel title = new JLabel("Διαχείριση Επιστροφών", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 20));

        String[] columns = {"ID", "User", "Movie", "Amount", "Reason", "Status"};
        DefaultTableModel model = new DefaultTableModel(columns, 0);
        JTable table = new JTable(model);

        loadRefundsData(model);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton btnBack = new JButton("Πίσω");
        JButton btnApprove = new JButton("Έγκριση");
        JButton btnReject = new JButton("Απόρριψη");
        JButton btnRefresh = new JButton("Ανανέωση");

        btnBack.addActionListener(e -> cardLayout.show(mainPanel, "AdminHome"));

        btnApprove.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                int refundId = (int) model.getValueAt(row, 0);
                processRefund(refundId, "approved");
                loadRefundsData(model);
            }
        });

        btnReject.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                int refundId = (int) model.getValueAt(row, 0);
                processRefund(refundId, "rejected");
                loadRefundsData(model);
            }
        });

        btnRefresh.addActionListener(e -> loadRefundsData(model));

        buttonPanel.add(btnBack);
        buttonPanel.add(btnApprove);
        buttonPanel.add(btnReject);
        buttonPanel.add(btnRefresh);

        panel.add(title, BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    // CONTINUATION OF TicketBook.java - Part 3
// Add these helper methods to the TicketBook class
    // ===============================
    // AUTHENTICATION METHODS
    // ===============================
    private void registerUser(String username, String email, String password,
            String invitationKey, boolean enable2FA) {
        String hashedPassword = hashPassword(password);
        if (hashedPassword == null) {
            JOptionPane.showMessageDialog(frame, "Σφάλμα κρυπτογράφησης κωδικού");
            return;
        }

        String role = "user";
        Integer invitationId = null;

        // Check admin invitation if provided
        if (!invitationKey.isEmpty()) {
            String checkInvitation = """
                SELECT id FROM admin_invitations 
                WHERE invitation_key = ? 
                AND used_by IS NULL 
                AND expires_at > NOW()
            """;

            try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(checkInvitation)) {
                st.setString(1, invitationKey);
                ResultSet rs = st.executeQuery();
                if (rs.next()) {
                    invitationId = rs.getInt("id");
                    role = "admin";
                } else {
                    JOptionPane.showMessageDialog(frame, "Μη έγκυρος ή ληγμένος κωδικός πρόσκλησης");
                    return;
                }
            } catch (SQLException e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(frame, "Σφάλμα ελέγχου πρόσκλησης");
                return;
            }
        }

        // Generate TOTP secret if 2FA enabled
        String totpSecret = enable2FA ? generateTOTPSecret() : null;

        try (Connection c = createConnection()) {
            c.setAutoCommit(false);

            // Check if user exists
            String checkSql = "SELECT COUNT(*) FROM users WHERE username = ? OR email = ?";
            try (PreparedStatement st = c.prepareStatement(checkSql)) {
                st.setString(1, username);
                st.setString(2, email);
                ResultSet rs = st.executeQuery();
                rs.next();
                if (rs.getInt(1) > 0) {
                    JOptionPane.showMessageDialog(frame, "Το username ή email υπάρχει ήδη");
                    return;
                }
            }

            // Insert new user
            String insertSql = """
                INSERT INTO users (username, email, password, role, totp_secret, is_active) 
                VALUES (?, ?, ?, ?, ?, ?)
            """;

            int newUserId;
            try (PreparedStatement st = c.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                st.setString(1, username);
                st.setString(2, email);
                st.setString(3, hashedPassword);
                st.setString(4, role);
                st.setString(5, totpSecret);
                st.setBoolean(6, true);
                st.executeUpdate();

                ResultSet keys = st.getGeneratedKeys();
                keys.next();
                newUserId = keys.getInt(1);
            }

            // Mark invitation as used
            if (invitationId != null) {
                String updateInvitation = "UPDATE admin_invitations SET used_by = ?, used_at = NOW() WHERE id = ?";
                try (PreparedStatement st = c.prepareStatement(updateInvitation)) {
                    st.setInt(1, newUserId);
                    st.setInt(2, invitationId);
                    st.executeUpdate();
                }
            }

            c.commit();

            // Show 2FA setup if enabled
            if (totpSecret != null) {
                show2FASetupDialog(username, totpSecret);
            }

            JOptionPane.showMessageDialog(frame,
                    "Επιτυχής εγγραφή" + (role.equals("admin") ? " ως Διαχειριστής!" : "!"));
            cardLayout.show(mainPanel, "home");

        } catch (SQLException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Σφάλμα εγγραφής: " + e.getMessage());
        }
    }

    private void performLogin(String usernameOrEmail, String password, JLabel msgLabel) {
        String hashedPassword = hashPassword(password);
        if (hashedPassword == null) {
            msgLabel.setText("Σφάλμα κρυπτογράφησης");
            return;
        }

        String sql = """
        SELECT id, username, role, totp_secret, is_active 
        FROM users 
        WHERE (username = ? OR email = ?) AND password = ?
    """;

        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {

            st.setString(1, usernameOrEmail);
            st.setString(2, usernameOrEmail);
            st.setString(3, hashedPassword);

            ResultSet rs = st.executeQuery();
            if (rs.next()) {
                // Check if account is active
                if (!rs.getBoolean("is_active")) {
                    msgLabel.setText("Ο λογαριασμός έχει απενεργοποιηθεί");
                    return;
                }

                String totpSecret = rs.getString("totp_secret");

                // Check 2FA if enabled
                if (totpSecret != null && !totpSecret.isEmpty()) {
                    String code = JOptionPane.showInputDialog(frame,
                            "Εισάγετε τον 6ψήφιο κωδικό από την εφαρμογή 2FA:");

                    if (code == null || !validateTOTP(totpSecret, code)) {
                        msgLabel.setText("Μη έγκυρος κωδικός 2FA");
                        return;
                    }
                }

                // Successful login
                currentUserId = rs.getInt("id");
                currentUsername = rs.getString("username");
                currentUserRole = rs.getString("role");

                if ("admin".equals(currentUserRole)) {
                    mainPanel.add(buildAdminHome(), "AdminHome");
                    cardLayout.show(mainPanel, "AdminHome");
                } else {
                    mainPanel.add(buildPostLoginHome(), "postLoginHome");
                    cardLayout.show(mainPanel, "postLoginHome");
                }
            } else {
                msgLabel.setText("Λάθος στοιχεία σύνδεσης");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            msgLabel.setText("Σφάλμα σύνδεσης: " + e.getMessage());
        }
    }

    private void logout() {
        currentUserId = -1;
        currentUsername = null;
        currentUserRole = null;

        // Clear cached panels
        cachedMoviesPanel = null;
        cachedShowtimesPanel = null;
        lastMovieId = -1;

        cardLayout.show(mainPanel, "home");
    }

    // ===============================
    // 2FA METHODS
    // ===============================
    private String generateTOTPSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        Base32 base32 = new Base32();
        return base32.encodeToString(bytes);
    }

    private void show2FASetupDialog(String username, String secret) {
        String otpAuthUri = String.format(
                "otpauth://totp/TicketBook:%s?secret=%s&issuer=TicketBook",
                username, secret
        );

        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(otpAuthUri, BarcodeFormat.QR_CODE, 300, 300);
            BufferedImage qrImage = MatrixToImageWriter.toBufferedImage(bitMatrix);

            JDialog dialog = new JDialog(frame, "2FA Setup", true);
            dialog.setLayout(new BorderLayout());

            JPanel topPanel = new JPanel();
            topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
            topPanel.add(new JLabel("Σαρώστε τον QR code με Google Authenticator:"));
            topPanel.add(Box.createRigidArea(new Dimension(0, 10)));
            topPanel.add(new JLabel("Manual Key: " + secret));

            JLabel qrLabel = new JLabel(new ImageIcon(qrImage));
            qrLabel.setHorizontalAlignment(SwingConstants.CENTER);

            JButton btnOk = new JButton("OK");
            btnOk.addActionListener(e -> dialog.dispose());

            dialog.add(topPanel, BorderLayout.NORTH);
            dialog.add(qrLabel, BorderLayout.CENTER);
            dialog.add(btnOk, BorderLayout.SOUTH);

            dialog.setSize(400, 450);
            dialog.setLocationRelativeTo(frame);
            dialog.setVisible(true);

        } catch (WriterException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Σφάλμα δημιουργίας QR code");
        }
    }

    private boolean validateTOTP(String secret, String code) {
        try {
            Base32 base32 = new Base32();
            byte[] bytes = base32.decode(secret);
            long timeStamp = System.currentTimeMillis() / 1000L / 30L;

            // Check current and adjacent time windows
            for (int i = -1; i <= 1; i++) {
                String hash = generateTOTPCode(bytes, timeStamp + i);
                if (hash.equals(code)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private String generateTOTPCode(byte[] key, long timeStamp) throws Exception {
        byte[] data = new byte[8];
        long value = timeStamp;
        for (int i = 8; i-- > 0; value >>>= 8) {
            data[i] = (byte) value;
        }

        SecretKeySpec signKey = new SecretKeySpec(key, "HmacSHA1");
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(signKey);
        byte[] hash = mac.doFinal(data);

        int offset = hash[hash.length - 1] & 0xf;
        int otp = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);

        return String.format("%06d", otp % 1000000);
    }

    // ===============================
    // FAVORITES METHODS
    // ===============================
    private void toggleFavorite(int movieId) {
        String checkSql = "SELECT id FROM favorites WHERE user_id = ? AND movie_id = ?";
        String deleteSql = "DELETE FROM favorites WHERE user_id = ? AND movie_id = ?";
        String insertSql = "INSERT INTO favorites (user_id, movie_id) VALUES (?, ?)";

        try (Connection c = createConnection()) {
            try (PreparedStatement st = c.prepareStatement(checkSql)) {
                st.setInt(1, currentUserId);
                st.setInt(2, movieId);
                ResultSet rs = st.executeQuery();

                if (rs.next()) {
                    // Remove from favorites
                    try (PreparedStatement st2 = c.prepareStatement(deleteSql)) {
                        st2.setInt(1, currentUserId);
                        st2.setInt(2, movieId);
                        st2.executeUpdate();
                        JOptionPane.showMessageDialog(frame, "Αφαιρέθηκε από τις αγαπημένες");
                    }
                } else {
                    // Add to favorites
                    try (PreparedStatement st2 = c.prepareStatement(insertSql)) {
                        st2.setInt(1, currentUserId);
                        st2.setInt(2, movieId);
                        st2.executeUpdate();
                        JOptionPane.showMessageDialog(frame, "Προστέθηκε στις αγαπημένες");
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Σφάλμα: " + e.getMessage());
        }
    }

    private void loadMoviesWithFavorites(DefaultListModel<MovieItem> model, String filter) {
        model.clear();

        String sql = """
        SELECT m.id, m.title, m.picture_url, m.duration, m.stars,
               CASE WHEN f.id IS NOT NULL THEN 1 ELSE 0 END as is_favorite
        FROM movies m
        LEFT JOIN favorites f ON m.id = f.movie_id AND f.user_id = ?
        WHERE m.title LIKE ?
        ORDER BY m.title
    """;

        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setInt(1, currentUserId);
            st.setString(2, "%" + filter + "%");
            ResultSet rs = st.executeQuery();

            while (rs.next()) {
                MovieItem item = createMovieItem(rs);
                item.setFavorite(rs.getInt("is_favorite") == 1);
                model.addElement(item);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void loadFavoriteMovies(DefaultListModel<MovieItem> model) {
        model.clear();
        String sql = """
            SELECT m.id, m.title, m.picture_url, m.duration, m.stars
            FROM movies m
            JOIN favorites f ON m.id = f.movie_id
            WHERE f.user_id = ?
            ORDER BY f.created_at DESC
        """;

        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setInt(1, currentUserId);
            ResultSet rs = st.executeQuery();

            while (rs.next()) {
                MovieItem item = createMovieItem(rs);
                item.setFavorite(true);
                model.addElement(item);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ===============================
    // BOOKING METHODS
    // ===============================
    private void loadUserBookings(DefaultListModel<String> model, List<Integer> bookingIds,
            List<LocalDateTime> showtimes, List<String> statuses) {
        model.clear();
        bookingIds.clear();
        showtimes.clear();
        statuses.clear();

        String sql = """
        SELECT b.id, m.title, s.show_time, b.seat_number, b.status, b.canceled_at,
               p.amount, p.status as payment_status,
               (SELECT r.status FROM refunds r WHERE r.payment_id = p.id 
                ORDER BY r.requested_at DESC LIMIT 1) as refund_status
        FROM bookings b
        JOIN showtimes s ON b.showtime_id = s.id
        JOIN movies m ON s.movie_id = m.id
        LEFT JOIN payments p ON p.booking_id = b.id
        WHERE b.user_id = ?
        ORDER BY s.show_time DESC
    """;

        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setInt(1, currentUserId);
            ResultSet rs = st.executeQuery();

            while (rs.next()) {
                int bookingId = rs.getInt("id");
                LocalDateTime showtime = rs.getTimestamp("show_time").toLocalDateTime();
                String status = rs.getString("status");
                String refundStatus = rs.getString("refund_status");

                bookingIds.add(bookingId);
                showtimes.add(showtime);
                statuses.add(status);

                String day = capitalizeGreekDay(showtime.getDayOfWeek());

                // Build status info
                String statusInfo = "";
                if (rs.getTimestamp("canceled_at") != null) {
                    statusInfo = " [ΑΚΥΡΩΜΕΝΗ]";
                } else if (refundStatus != null) {
                    switch (refundStatus) {
                        case "pending":
                            statusInfo = " [ΑΙΤΗΜΑ ΕΠΙΣΤΡΟΦΗΣ ΣΕ ΑΝΑΜΟΝΗ]";
                            break;
                        case "approved":
                        case "completed":
                            statusInfo = " [ΕΠΙΣΤΡΟΦΗ ΕΓΚΡΙΘΗΚΕ]";
                            break;
                        case "rejected":
                            statusInfo = " [ΕΠΙΣΤΡΟΦΗ ΑΠΟΡΡΙΦΘΗΚΕ]";
                            break;
                    }
                }

                String paymentInfo = "";
                if (rs.getDouble("amount") > 0) {
                    paymentInfo = String.format(" - €%.2f", rs.getDouble("amount"));
                }

                String entry = String.format(
                        "%s %d/%d %02d:%02d - %s (Θέση %s)%s%s",
                        day, showtime.getDayOfMonth(), showtime.getMonthValue(),
                        showtime.getHour(), showtime.getMinute(),
                        rs.getString("title"), rs.getString("seat_number"),
                        paymentInfo, statusInfo
                );

                model.addElement(entry);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void cancelBooking(int bookingId) {
        int confirm = JOptionPane.showConfirmDialog(frame,
                "Είστε σίγουροι για την ακύρωση;", "Επιβεβαίωση",
                JOptionPane.YES_NO_OPTION);

        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        try (Connection c = createConnection()) {
            c.setAutoCommit(false);

            // Get booking details
            String getDetails = "SELECT showtime_id, seat_number FROM bookings WHERE id = ?";
            int showtimeId = 0;
            String seatNumber = "";

            try (PreparedStatement st = c.prepareStatement(getDetails)) {
                st.setInt(1, bookingId);
                ResultSet rs = st.executeQuery();
                if (rs.next()) {
                    showtimeId = rs.getInt("showtime_id");
                    seatNumber = rs.getString("seat_number");
                }
            }

            // Update booking
            String updateBooking = "UPDATE bookings SET canceled_at = NOW(), status = 'canceled' WHERE id = ?";
            try (PreparedStatement st = c.prepareStatement(updateBooking)) {
                st.setInt(1, bookingId);
                st.executeUpdate();
            }

            // Free the seat
            String updateSeat = "UPDATE seats SET is_available = 1 WHERE showtime_id = ? AND seat_label = ?";
            try (PreparedStatement st = c.prepareStatement(updateSeat)) {
                st.setInt(1, showtimeId);
                st.setString(2, seatNumber);
                st.executeUpdate();
            }

            // Process automatic refund
            processAutomaticRefund(bookingId, c);

            c.commit();
            JOptionPane.showMessageDialog(frame, "Η κράτηση ακυρώθηκε επιτυχώς");

        } catch (SQLException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Σφάλμα ακύρωσης: " + e.getMessage());
        }
    }

    private void processAutomaticRefund(int bookingId, Connection c) throws SQLException {
        String checkPayment = "SELECT id, amount FROM payments WHERE booking_id = ? AND status = 'completed'";
        try (PreparedStatement st = c.prepareStatement(checkPayment)) {
            st.setInt(1, bookingId);
            ResultSet rs = st.executeQuery();

            if (rs.next()) {
                int paymentId = rs.getInt("id");
                double amount = rs.getDouble("amount");

                // Create refund
                String createRefund = """
                    INSERT INTO refunds (payment_id, amount, reason, status, processed_at, processed_by)
                    VALUES (?, ?, 'Automatic refund - 24h cancellation', 'completed', NOW(), ?)
                """;

                try (PreparedStatement st2 = c.prepareStatement(createRefund)) {
                    st2.setInt(1, paymentId);
                    st2.setDouble(2, amount);
                    st2.setInt(3, currentUserId);
                    st2.executeUpdate();
                }

                // Update payment status
                try (PreparedStatement st2 = c.prepareStatement(
                        "UPDATE payments SET status = 'refunded' WHERE id = ?")) {
                    st2.setInt(1, paymentId);
                    st2.executeUpdate();
                }
            }
        }
    }

    // ===============================
    // SUPPORT TICKET METHODS
    // ===============================
    private void loadUserTickets(DefaultListModel<String> model, List<Integer> ticketIds) {
        model.clear();
        ticketIds.clear();

        String sql = """
            SELECT id, subject, status, created_at 
            FROM support_tickets 
            WHERE user_id = ? 
            ORDER BY created_at DESC
        """;

        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setInt(1, currentUserId);
            ResultSet rs = st.executeQuery();

            while (rs.next()) {
                ticketIds.add(rs.getInt("id"));

                String entry = String.format("[%s] %s - %s",
                        rs.getString("status").toUpperCase(),
                        rs.getString("subject"),
                        rs.getTimestamp("created_at").toLocalDateTime()
                                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                );

                model.addElement(entry);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void showNewTicketDialog() {
        JDialog dialog = new JDialog(frame, "Νέο Αίτημα Υποστήριξης", true);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(500, 400);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        JTextField subjectField = new JTextField(30);
        JTextArea messageArea = new JTextArea(10, 30);
        messageArea.setLineWrap(true);
        messageArea.setWrapStyleWord(true);

        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Θέμα:"), gbc);
        gbc.gridx = 1;
        panel.add(subjectField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        panel.add(new JLabel("Μήνυμα:"), gbc);
        gbc.gridx = 1;
        panel.add(new JScrollPane(messageArea), gbc);

        JButton btnSubmit = new JButton("Υποβολή");
        btnSubmit.addActionListener(e -> {
            String subject = subjectField.getText().trim();
            String message = messageArea.getText().trim();

            if (subject.isEmpty() || message.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Συμπληρώστε όλα τα πεδία");
                return;
            }

            createSupportTicket(subject, message);
            dialog.dispose();
        });

        dialog.add(panel, BorderLayout.CENTER);
        dialog.add(btnSubmit, BorderLayout.SOUTH);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private void createSupportTicket(String subject, String message) {
        try (Connection c = createConnection()) {
            // Create ticket
            String createTicket = "INSERT INTO support_tickets (user_id, subject) VALUES (?, ?)";
            int ticketId;

            try (PreparedStatement st = c.prepareStatement(createTicket, Statement.RETURN_GENERATED_KEYS)) {
                st.setInt(1, currentUserId);
                st.setString(2, subject);
                st.executeUpdate();

                ResultSet keys = st.getGeneratedKeys();
                keys.next();
                ticketId = keys.getInt(1);
            }

            // Add first message
            String addMessage = "INSERT INTO ticket_messages (ticket_id, sender_id, message) VALUES (?, ?, ?)";
            try (PreparedStatement st = c.prepareStatement(addMessage)) {
                st.setInt(1, ticketId);
                st.setInt(2, currentUserId);
                st.setString(3, message);
                st.executeUpdate();
            }

            JOptionPane.showMessageDialog(frame, "Το αίτημα υποβλήθηκε επιτυχώς");

        } catch (SQLException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Σφάλμα: " + e.getMessage());
        }
    }

    // CONTINUATION OF TicketBook.java - Part 4 (Final)
// Add these final methods and inner classes to complete the TicketBook class
    // ===============================
    // ADMIN METHODS
    // ===============================
    private void loadUsersData(DefaultTableModel model) {
        model.setRowCount(0);
        String sql = "SELECT id, username, email, role, totp_secret, is_active FROM users ORDER BY id";

        try (Connection c = createConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {

            while (rs.next()) {
                Object[] row = {
                    rs.getInt("id"),
                    rs.getString("username"),
                    rs.getString("email"),
                    rs.getString("role"),
                    rs.getString("totp_secret") != null ? "Yes" : "No",
                    rs.getBoolean("is_active") ? "Active" : "Inactive"
                };
                model.addRow(row);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void toggleUserActive(int userId) {
        String sql = "UPDATE users SET is_active = NOT is_active WHERE id = ?";
        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setInt(1, userId);
            st.executeUpdate();
            JOptionPane.showMessageDialog(frame, "Κατάσταση χρήστη ενημερώθηκε");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void changeUserRole(int userId) {
        String[] roles = {"user", "admin"};
        String newRole = (String) JOptionPane.showInputDialog(frame,
                "Επιλέξτε νέο ρόλο:", "Αλλαγή Ρόλου",
                JOptionPane.QUESTION_MESSAGE, null, roles, roles[0]);

        if (newRole != null) {
            String sql = "UPDATE users SET role = ? WHERE id = ?";
            try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {
                st.setString(1, newRole);
                st.setInt(2, userId);
                st.executeUpdate();
                JOptionPane.showMessageDialog(frame, "Ο ρόλος άλλαξε επιτυχώς");
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    private void loadInvitations(DefaultListModel<String> model) {
        model.clear();
        String sql = """
            SELECT ai.*, u1.username as created_by_name, u2.username as used_by_name
            FROM admin_invitations ai
            LEFT JOIN users u1 ON ai.created_by = u1.id
            LEFT JOIN users u2 ON ai.used_by = u2.id
            ORDER BY ai.created_at DESC
        """;

        try (Connection c = createConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {

            while (rs.next()) {
                String key = rs.getString("invitation_key");
                String createdBy = rs.getString("created_by_name");
                String usedBy = rs.getString("used_by_name");
                Timestamp expires = rs.getTimestamp("expires_at");

                String status = usedBy != null ? "Used by " + usedBy
                        : (expires.before(new Timestamp(System.currentTimeMillis())) ? "Expired" : "Active");

                String item = String.format("Key: %s... | Created by: %s | Status: %s",
                        key.substring(0, Math.min(10, key.length())),
                        createdBy, status);
                model.addElement(item);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private String generateInvitationKey() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void createAdminInvitation(String key) {
        String sql = "INSERT INTO admin_invitations (invitation_key, created_by, expires_at) VALUES (?, ?, ?)";

        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, key);
            st.setInt(2, currentUserId);
            // Set expiration to 7 days from now
            st.setTimestamp(3, new Timestamp(System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000));
            st.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Σφάλμα δημιουργίας πρόσκλησης");
        }
    }

    private void loadAllTickets(DefaultListModel<String> model, List<Integer> ticketIds) {
        model.clear();
        ticketIds.clear();

        String sql = """
            SELECT t.id, t.subject, t.status, t.created_at, u.username
            FROM support_tickets t
            JOIN users u ON t.user_id = u.id
            ORDER BY 
                CASE t.status 
                    WHEN 'open' THEN 1 
                    WHEN 'in_progress' THEN 2 
                    ELSE 3 
                END,
                t.created_at DESC
        """;

        try (Connection c = createConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {

            while (rs.next()) {
                ticketIds.add(rs.getInt("id"));

                String item = String.format("[%s] %s - %s (%s)",
                        rs.getString("status").toUpperCase(),
                        rs.getString("subject"),
                        rs.getString("username"),
                        rs.getTimestamp("created_at").toLocalDateTime()
                                .format(DateTimeFormatter.ofPattern("dd/MM HH:mm")));
                model.addElement(item);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void showTicketMessagesDialog(int ticketId) {
        JDialog dialog = new JDialog(frame, "Μηνύματα Ticket #" + ticketId, true);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(600, 500);

        JTextArea messagesArea = new JTextArea();
        messagesArea.setEditable(false);
        messagesArea.setFont(new Font("Monospaced", Font.PLAIN, 12));

        // Check ticket status
        String ticketStatus = "";
        String statusCheckSql = "SELECT status FROM support_tickets WHERE id = ?";
        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(statusCheckSql)) {
            st.setInt(1, ticketId);
            ResultSet rs = st.executeQuery();
            if (rs.next()) {
                ticketStatus = rs.getString("status");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        // Load messages
        loadTicketMessages(ticketId, messagesArea);

        // Add status label
        JLabel statusLabel = new JLabel("Status: " + ticketStatus.toUpperCase(), SwingConstants.CENTER);
        statusLabel.setFont(new Font("Arial", Font.BOLD, 14));
        if (ticketStatus.equals("closed")) {
            statusLabel.setForeground(Color.RED);
        } else if (ticketStatus.equals("in_progress")) {
            statusLabel.setForeground(Color.ORANGE);
        } else {
            statusLabel.setForeground(Color.GREEN);
        }

        dialog.add(statusLabel, BorderLayout.NORTH);
        dialog.add(new JScrollPane(messagesArea), BorderLayout.CENTER);

        // Reply panel - ONLY if ticket is not closed
        if (!ticketStatus.equals("closed")) {
            JPanel replyPanel = new JPanel(new BorderLayout());
            JTextArea replyArea = new JTextArea(3, 40);
            JButton btnSend = new JButton("Αποστολή");

            btnSend.addActionListener(e -> {
                String reply = replyArea.getText().trim();
                if (!reply.isEmpty()) {
                    sendTicketReply(ticketId, reply);
                    dialog.dispose();
                    showTicketMessagesDialog(ticketId); // Refresh
                }
            });

            replyPanel.add(new JScrollPane(replyArea), BorderLayout.CENTER);
            replyPanel.add(btnSend, BorderLayout.EAST);
            dialog.add(replyPanel, BorderLayout.SOUTH);
        } else {
            // If closed, show message
            JLabel closedLabel = new JLabel("Το ticket είναι κλειστό - Δεν μπορείτε να στείλετε μηνύματα",
                    SwingConstants.CENTER);
            closedLabel.setForeground(Color.RED);
            closedLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            dialog.add(closedLabel, BorderLayout.SOUTH);
        }

        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private void loadTicketMessages(int ticketId, JTextArea messagesArea) {
        String sql = """
            SELECT m.message, m.created_at, u.username, u.role
            FROM ticket_messages m
            JOIN users u ON m.sender_id = u.id
            WHERE m.ticket_id = ?
            ORDER BY m.created_at
        """;

        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setInt(1, ticketId);
            ResultSet rs = st.executeQuery();

            while (rs.next()) {
                String username = rs.getString("username");
                String role = rs.getString("role");
                String message = rs.getString("message");
                Timestamp created = rs.getTimestamp("created_at");

                String sender = username + (role.equals("admin") ? " [ADMIN]" : " [USER]");
                messagesArea.append(String.format("[%s] %s:\n%s\n\n",
                        created.toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM HH:mm")),
                        sender, message));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void sendTicketReply(int ticketId, String message) {
        try (Connection c = createConnection()) {
            // Add message
            String addMessage = "INSERT INTO ticket_messages (ticket_id, sender_id, message) VALUES (?, ?, ?)";
            try (PreparedStatement st = c.prepareStatement(addMessage)) {
                st.setInt(1, ticketId);
                st.setInt(2, currentUserId);
                st.setString(3, message);
                st.executeUpdate();
            }

            // Update ticket status if admin
            if ("admin".equals(currentUserRole)) {
                String updateStatus = "UPDATE support_tickets SET status = 'in_progress' WHERE id = ? AND status = 'open'";
                try (PreparedStatement st = c.prepareStatement(updateStatus)) {
                    st.setInt(1, ticketId);
                    st.executeUpdate();
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void closeTicket(int ticketId) {
        String sql = "UPDATE support_tickets SET status = 'closed' WHERE id = ?";
        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setInt(1, ticketId);
            st.executeUpdate();
            JOptionPane.showMessageDialog(frame, "Το ticket έκλεισε");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void loadRefundsData(DefaultTableModel model) {
        model.setRowCount(0);
        String sql = """
        SELECT r.id, u.username, m.title, r.amount, r.reason, r.status
        FROM refunds r
        JOIN payments p ON r.payment_id = p.id
        JOIN bookings b ON p.booking_id = b.id
        JOIN users u ON b.user_id = u.id
        JOIN showtimes s ON b.showtime_id = s.id
        JOIN movies m ON s.movie_id = m.id
        WHERE r.status = 'pending'  -- Μόνο pending refunds
        ORDER BY r.requested_at DESC
    """;

        try (Connection c = createConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {

            while (rs.next()) {
                Object[] row = {
                    rs.getInt("id"),
                    rs.getString("username"),
                    rs.getString("title"),
                    String.format("€%.2f", rs.getDouble("amount")),
                    rs.getString("reason"),
                    rs.getString("status")
                };
                model.addRow(row);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void processRefund(int refundId, String status) {
        try (Connection c = createConnection()) {
            c.setAutoCommit(false);

            try {
                // Update refund status
                String updateRefundSql = "UPDATE refunds SET status = ?, processed_at = NOW(), processed_by = ? WHERE id = ?";
                try (PreparedStatement st = c.prepareStatement(updateRefundSql)) {
                    st.setString(1, status);
                    st.setInt(2, currentUserId);
                    st.setInt(3, refundId);
                    st.executeUpdate();
                }

                if (status.equals("approved")) {
                    // Get payment and booking information
                    String getInfoSql = """
                    SELECT p.id as payment_id, p.booking_id, b.showtime_id, b.seat_number
                    FROM refunds r
                    JOIN payments p ON r.payment_id = p.id
                    JOIN bookings b ON p.booking_id = b.id
                    WHERE r.id = ?
                """;

                    int paymentId = 0;
                    int bookingId = 0;
                    int showtimeId = 0;
                    String seatNumber = "";

                    try (PreparedStatement st = c.prepareStatement(getInfoSql)) {
                        st.setInt(1, refundId);
                        ResultSet rs = st.executeQuery();
                        if (rs.next()) {
                            paymentId = rs.getInt("payment_id");
                            bookingId = rs.getInt("booking_id");
                            showtimeId = rs.getInt("showtime_id");
                            seatNumber = rs.getString("seat_number");
                        }
                    }

                    // Update payment status to refunded
                    String updatePaymentSql = "UPDATE payments SET status = 'refunded' WHERE id = ?";
                    try (PreparedStatement st = c.prepareStatement(updatePaymentSql)) {
                        st.setInt(1, paymentId);
                        st.executeUpdate();
                    }

                    // Cancel the booking
                    String cancelBookingSql = "UPDATE bookings SET status = 'canceled', canceled_at = NOW() WHERE id = ?";
                    try (PreparedStatement st = c.prepareStatement(cancelBookingSql)) {
                        st.setInt(1, bookingId);
                        st.executeUpdate();
                    }

                    // Make the seat available again
                    String freeSeatSql = "UPDATE seats SET is_available = 1 WHERE showtime_id = ? AND seat_label = ?";
                    try (PreparedStatement st = c.prepareStatement(freeSeatSql)) {
                        st.setInt(1, showtimeId);
                        st.setString(2, seatNumber);
                        st.executeUpdate();
                    }

                    // Update refund status to completed
                    String completeRefundSql = "UPDATE refunds SET status = 'completed' WHERE id = ?";
                    try (PreparedStatement st = c.prepareStatement(completeRefundSql)) {
                        st.setInt(1, refundId);
                        st.executeUpdate();
                    }
                }

                c.commit();

                JOptionPane.showMessageDialog(frame,
                        status.equals("approved")
                        ? "Επιστροφή εγκρίθηκε και η κράτηση ακυρώθηκε"
                        : "Επιστροφή απορρίφθηκε");

            } catch (SQLException e) {
                c.rollback();
                throw e;
            }

        } catch (SQLException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Σφάλμα επεξεργασίας επιστροφής: " + e.getMessage());
        }
    }

    private void requestRefund(int bookingId) {
        // Check if refund already exists for this booking
        String checkRefundSql = """
        SELECT r.status, r.requested_at 
        FROM refunds r 
        JOIN payments p ON r.payment_id = p.id 
        WHERE p.booking_id = ? 
        AND r.status IN ('pending', 'approved', 'completed')
    """;

        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(checkRefundSql)) {
            st.setInt(1, bookingId);
            ResultSet rs = st.executeQuery();

            if (rs.next()) {
                String status = rs.getString("status");
                Timestamp requestedAt = rs.getTimestamp("requested_at");

                String message;
                if (status.equals("pending")) {
                    message = String.format(
                            "Υπάρχει ήδη αίτημα επιστροφής σε αναμονή για αυτή την κράτηση\n"
                            + "Ημερομηνία υποβολής: %s\n"
                            + "Παρακαλούμε περιμένετε την απάντηση του διαχειριστή.",
                            requestedAt.toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    );
                } else if (status.equals("approved") || status.equals("completed")) {
                    message = "Η επιστροφή για αυτή την κράτηση έχει ήδη εγκριθεί.";
                } else {
                    message = "Υπάρχει ήδη αίτημα επιστροφής για αυτή την κράτηση.";
                }

                JOptionPane.showMessageDialog(frame, message, "Αίτημα Υπάρχει", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Σφάλμα ελέγχου: " + e.getMessage());
            return;
        }

        // Check if booking is already canceled
        String checkBookingSql = "SELECT status, canceled_at FROM bookings WHERE id = ?";
        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(checkBookingSql)) {
            st.setInt(1, bookingId);
            ResultSet rs = st.executeQuery();

            if (rs.next()) {
                String status = rs.getString("status");
                if (status.equals("canceled") || rs.getTimestamp("canceled_at") != null) {
                    JOptionPane.showMessageDialog(frame,
                            "Δεν μπορείτε να ζητήσετε επιστροφή για ακυρωμένη κράτηση.",
                            "Κράτηση Ακυρωμένη",
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        // If no existing refund, proceed with the dialog
        JDialog dialog = new JDialog(frame, "Αίτημα Επιστροφής", true);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(400, 300);

        JTextArea reasonArea = new JTextArea(5, 30);
        reasonArea.setLineWrap(true);
        reasonArea.setWrapStyleWord(true);

        JButton btnSubmit = new JButton("Υποβολή");
        btnSubmit.addActionListener(e -> {
            String reason = reasonArea.getText().trim();
            if (reason.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Παρακαλώ εισάγετε τον λόγο επιστροφής");
                return;
            }

            submitRefundRequest(bookingId, reason);
            dialog.dispose();

            // ΑΝΑΝΕΩΣΗ ΑΜΕΣΑ ΜΕΤΑ ΤΟ ΚΛΕΙΣΙΜΟ ΤΟΥ DIALOG
            JPanel freshBookingsPanel = buildMyBookingsPage();
            mainPanel.add(freshBookingsPanel, "myBookings");
            cardLayout.show(mainPanel, "myBookings");
        });

        dialog.add(new JLabel("Λόγος επιστροφής:"), BorderLayout.NORTH);
        dialog.add(new JScrollPane(reasonArea), BorderLayout.CENTER);
        dialog.add(btnSubmit, BorderLayout.SOUTH);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private void submitRefundRequest(int bookingId, String reason) {
        try (Connection c = createConnection()) {
            // Check if payment exists
            String checkSql = "SELECT id, amount FROM payments WHERE booking_id = ? AND status = 'completed'";
            try (PreparedStatement st = c.prepareStatement(checkSql)) {
                st.setInt(1, bookingId);
                ResultSet rs = st.executeQuery();

                if (rs.next()) {
                    int paymentId = rs.getInt("id");
                    double amount = rs.getDouble("amount");

                    // Create refund request
                    String refundSql = "INSERT INTO refunds (payment_id, amount, reason, status) VALUES (?, ?, ?, 'pending')";
                    try (PreparedStatement st2 = c.prepareStatement(refundSql)) {
                        st2.setInt(1, paymentId);
                        st2.setDouble(2, amount);
                        st2.setString(3, reason);
                        st2.executeUpdate();

                        JOptionPane.showMessageDialog(frame, "Το αίτημα επιστροφής υποβλήθηκε");

                        // ΑΝΑΝΕΩΣΗ ΤΗΣ ΣΕΛΙΔΑΣ ΚΡΑΤΗΣΕΩΝ ΑΥΤΟΜΑΤΑ
                        JPanel freshBookingsPanel = buildMyBookingsPage();
                        mainPanel.add(freshBookingsPanel, "myBookings");
                        cardLayout.show(mainPanel, "myBookings");
                    }
                } else {
                    JOptionPane.showMessageDialog(frame, "Δεν βρέθηκε πληρωμή για αυτή την κράτηση");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Σφάλμα: " + e.getMessage());
        }
    }

    private void showBookingQR(int bookingId) {
        String sql = "SELECT qr_code FROM bookings WHERE id = ? AND canceled_at IS NULL";
        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setInt(1, bookingId);
            ResultSet rs = st.executeQuery();

            if (rs.next()) {
                String qrCode = rs.getString("qr_code");
                if (qrCode != null && !qrCode.isEmpty()) {
                    showQRCodeDialog(qrCode);
                } else {
                    JOptionPane.showMessageDialog(frame, "Δεν υπάρχει QR code");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void saveMovieWithImage(Integer movieId, String title, String imageFilePath,
            String duration, String stars, String description) {
        // Πρώτα αποθήκευση στη βάση
        String sql;
        if (movieId == null) {
            sql = "INSERT INTO movies (title, picture_url, duration, stars, dc) VALUES (?, ?, ?, ?, ?)";
        } else {
            sql = "UPDATE movies SET title = ?, picture_url = ?, duration = ?, stars = ?, dc = ? WHERE id = ?";
        }

        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            st.setString(1, title);
            st.setString(2, "local"); // Απλά σημείωμα ότι είναι τοπική
            st.setString(3, duration.trim().isEmpty() ? null : duration);
            st.setString(4, stars.trim().isEmpty() ? null : stars);
            st.setString(5, description.trim().isEmpty() ? null : description);

            if (movieId != null) {
                st.setInt(6, movieId);
            }

            st.executeUpdate();

            // Αν είναι νέα ταινία, πάρε το ID
            if (movieId == null) {
                ResultSet keys = st.getGeneratedKeys();
                if (keys.next()) {
                    movieId = keys.getInt(1);
                }
            }

            // Αντιγραφή εικόνας στο resources folder
            if (imageFilePath != null && !imageFilePath.isEmpty()) {
                File sourceFile = new File(imageFilePath);
                if (sourceFile.exists()) {
                    // Δημιουργία resources/images αν δεν υπάρχει
                    File imagesDir = new File("src/main/resources/images");
                    if (!imagesDir.exists()) {
                        imagesDir.mkdirs();
                    }

                    // Αντιγραφή με το movie ID ως όνομα
                    File destFile = new File(imagesDir, movieId + ".jpg");
                    Files.copy(sourceFile.toPath(), destFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }

            JOptionPane.showMessageDialog(frame,
                    movieId == null ? "Η ταινία προστέθηκε!" : "Η ταινία ενημερώθηκε!");

        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Σφάλμα: " + e.getMessage());
        }
    }

    private JPanel buildShowtimesPage(int movieId) {
        // Αν είναι η ίδια ταινία, χρήση cached
        if (cachedShowtimesPanel != null && lastMovieId == movieId) {
            return cachedShowtimesPanel;
        }

        lastMovieId = movieId;
        JPanel pagePanel = new JPanel(new BorderLayout(10, 10));
        pagePanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        // Left panel με προβολές (ίδιο με πριν)
        JPanel leftPanel = new JPanel(new BorderLayout(10, 10));
        DefaultListModel<String> model = new DefaultListModel<>();
        JList<String> list = new JList<>(model);

        final int[] futureShowtimesCount = {0};

        String sql = """
        SELECT id, show_time 
        FROM showtimes 
        WHERE movie_id = ? 
        AND show_time > NOW()
        ORDER BY show_time
    """;

        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setInt(1, movieId);
            ResultSet rs = st.executeQuery();
            while (rs.next()) {
                futureShowtimesCount[0]++;
                int sid = rs.getInt("id");
                LocalDateTime dt = rs.getTimestamp("show_time").toLocalDateTime();
                String day = capitalizeGreekDay(dt.getDayOfWeek());
                String label = String.format(
                        "%s %d/%d %02d:%02d | %d",
                        day,
                        dt.getDayOfMonth(), dt.getMonthValue(),
                        dt.getHour(), dt.getMinute(),
                        sid
                );
                model.addElement(label);
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }

        if (futureShowtimesCount[0] == 0) {
            model.addElement("Δεν υπάρχουν διαθέσιμες προβολές");
        }

        leftPanel.add(new JLabel("Διαθέσιμες προβολές:"), BorderLayout.NORTH);
        leftPanel.add(new JScrollPane(list), BorderLayout.CENTER);

        // Right panel - INSTANT image από cache
        JPanel rightPanel = new JPanel(new BorderLayout(10, 10));
        rightPanel.setPreferredSize(new Dimension(300, 0));
        rightPanel.setBorder(BorderFactory.createTitledBorder("Πληροφορίες Ταινίας"));

        JLabel movieImageLabel = new JLabel();
        movieImageLabel.setPreferredSize(new Dimension(200, 250));
        movieImageLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JLabel movieTitleLabel = new JLabel();
        movieTitleLabel.setFont(new Font("Arial", Font.BOLD, 16));
        movieTitleLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JTextArea movieDescArea = new JTextArea();
        movieDescArea.setWrapStyleWord(true);
        movieDescArea.setLineWrap(true);
        movieDescArea.setEditable(false);
        movieDescArea.setOpaque(false);
        movieDescArea.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        String movieSql = "SELECT title, picture_url, dc FROM movies WHERE id=?";
        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(movieSql)) {
            st.setInt(1, movieId);
            ResultSet rs = st.executeQuery();
            if (rs.next()) {
                String title = rs.getString("title");
                String pictureUrl = rs.getString("picture_url");
                String description = rs.getString("dc");

                movieTitleLabel.setText(title);

                if (description != null && !description.trim().isEmpty()) {
                    movieDescArea.setText(description);
                } else {
                    movieDescArea.setText("Δεν υπάρχει διαθέσιμη περιγραφή για αυτή την ταινία.");
                }

                String localImagePath = "src/main/resources/images/" + movieId + ".jpg";
                File imageFile = new File(localImagePath);

                if (imageFile.exists()) {
                    ImageIcon icon = new ImageIcon(localImagePath);
                    Image scaled = icon.getImage().getScaledInstance(200, 250, Image.SCALE_SMOOTH);
                    movieImageLabel.setIcon(new ImageIcon(scaled));
                } else {
                    movieImageLabel.setText("Δεν υπάρχει εικόνα");
                    movieImageLabel.setBorder(BorderFactory.createLineBorder(Color.GRAY));
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }

        rightPanel.add(movieImageLabel, BorderLayout.NORTH);
        rightPanel.add(movieTitleLabel, BorderLayout.CENTER);
        rightPanel.add(new JScrollPane(movieDescArea), BorderLayout.SOUTH);

        // Buttons
        JButton btnBack = new JButton("Πίσω");
        JButton btnNext = new JButton("Επόμενο");

        btnBack.addActionListener(e -> cardLayout.show(mainPanel, "movies"));
        btnNext.addActionListener(e -> {
            if (futureShowtimesCount[0] == 0) {
                JOptionPane.showMessageDialog(frame, "Δεν υπάρχουν διαθέσιμες προβολές");
                return;
            }
            String sel = list.getSelectedValue();
            if (sel == null || sel.equals("Δεν υπάρχουν διαθέσιμες προβολές")) {
                JOptionPane.showMessageDialog(frame, "Επίλεξε προβολή");
                return;
            }
            int idx = sel.lastIndexOf('|');
            int sid = Integer.parseInt(sel.substring(idx + 1).trim());
            mainPanel.add(buildSeatsPage(sid), "seats");
            cardLayout.show(mainPanel, "seats");
        });

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(btnBack);
        buttonPanel.add(btnNext);

        pagePanel.add(leftPanel, BorderLayout.CENTER);
        pagePanel.add(rightPanel, BorderLayout.EAST);
        pagePanel.add(buttonPanel, BorderLayout.SOUTH);

        cachedShowtimesPanel = pagePanel;
        return pagePanel;
    }

    private void cleanupPastShowtimes() {
        String sql = """
        DELETE FROM showtimes 
        WHERE show_time < DATE_SUB(NOW(), INTERVAL 1 DAY)
    """;

        try (Connection c = createConnection(); Statement st = c.createStatement()) {
            int deleted = st.executeUpdate(sql);
            if (deleted > 0) {
                System.out.println("Deleted " + deleted + " past showtimes");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private JPanel buildSeatsPage(int showtimeId) {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JPanel north = new JPanel(new GridLayout(2, 1, 0, 5));
        JLabel screenLabel = new JLabel("ΟΘΟΝΗ", SwingConstants.CENTER);
        screenLabel.setOpaque(true);
        screenLabel.setBackground(Color.DARK_GRAY);
        screenLabel.setForeground(Color.WHITE);
        screenLabel.setFont(screenLabel.getFont().deriveFont(Font.BOLD, 16f));
        north.add(screenLabel);
        JLabel instrLabel = new JLabel("Επίλεξε τη θέση σου", SwingConstants.CENTER);
        instrLabel.setFont(instrLabel.getFont().deriveFont(Font.BOLD, 14f));
        north.add(instrLabel);
        panel.add(north, BorderLayout.NORTH);

        JButton btnBack = new JButton("Πίσω");
        JButton btnBook = new JButton("Κράτηση");

        JPanel grid = new JPanel(new GridLayout(3, 10, 5, 5));
        List<JButton> buttons = new ArrayList<>();

        String sql = "SELECT seat_label,is_available FROM seats "
                + "WHERE showtime_id=? ORDER BY "
                + "SUBSTRING(seat_label,1,1), "
                + "CAST(SUBSTRING(seat_label,2) AS UNSIGNED)";

        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setInt(1, showtimeId);
            ResultSet rs = st.executeQuery();
            while (rs.next()) {
                String sl = rs.getString("seat_label");
                boolean av = rs.getBoolean("is_available");
                JButton b = new JButton(sl);
                b.setPreferredSize(new Dimension(20, 20));
                b.setBackground(av ? Color.WHITE : Color.RED);
                b.setOpaque(true);
                b.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
                b.addActionListener(e -> {
                    if (!av) {
                        JOptionPane.showMessageDialog(frame, "Μη διαθέσιμη θέση");
                        return;
                    }
                    buttons.forEach(x -> {
                        if (x == b) {
                            x.setBackground(Color.GREEN);
                        } else if (x.getBackground() == Color.GREEN) {
                            x.setBackground(Color.WHITE);
                        }
                    });
                });
                buttons.add(b);
                grid.add(b);
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }

        btnBack.addActionListener(e -> cardLayout.show(mainPanel, "showtimes"));
        btnBook.addActionListener(e -> {
            JButton sel = buttons.stream()
                    .filter(x -> x.getBackground() == Color.GREEN)
                    .findFirst()
                    .orElse(null);
            if (sel == null) {
                JOptionPane.showMessageDialog(frame, "Επίλεξε θέση!");
                return;
            }
            String seat = sel.getText();

            // Mock payment
            double ticketPrice = 8.50;
            int confirm = JOptionPane.showConfirmDialog(frame,
                    String.format("Τιμή εισιτηρίου: €%.2f\nΣυνέχεια με πληρωμή;", ticketPrice),
                    "Επιβεβαίωση Πληρωμής",
                    JOptionPane.YES_NO_OPTION);

            if (confirm != JOptionPane.YES_OPTION) {
                return;
            }

            String movieInfo = getMovieInfoForShowtime(showtimeId);
            if (movieInfo == null) {
                JOptionPane.showMessageDialog(frame, "Σφάλμα φόρτωσης πληροφοριών ταινίας");
                return;
            }

            String qrData = String.format(
                    "TicketBook\nUser: %s\nMovie: %s\nSeat: %s\nTime: %s",
                    currentUsername,
                    movieInfo,
                    seat,
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
            );

            String qrCodeBase64 = generateQRCodeImage(qrData);
            if (qrCodeBase64 == null) {
                JOptionPane.showMessageDialog(frame, "Σφάλμα δημιουργίας QR code");
                return;
            }

            try (Connection c = createConnection()) {
                c.setAutoCommit(false);

                // Insert booking
                int bookingId;
                String insertSql = "INSERT INTO bookings(user_id,showtime_id,seat_number,status,qr_code) VALUES(?,?,?,?,?)";
                try (PreparedStatement ps = c.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, currentUserId);
                    ps.setInt(2, showtimeId);
                    ps.setString(3, seat);
                    ps.setString(4, "confirmed");
                    ps.setString(5, qrCodeBase64);
                    ps.executeUpdate();

                    ResultSet keys = ps.getGeneratedKeys();
                    keys.next();
                    bookingId = keys.getInt(1);
                }

                // Create payment record
                String paymentSql = "INSERT INTO payments(booking_id, amount, payment_method, status) VALUES(?,?,?,?)";
                try (PreparedStatement ps = c.prepareStatement(paymentSql)) {
                    ps.setInt(1, bookingId);
                    ps.setDouble(2, ticketPrice);
                    ps.setString(3, "card");
                    ps.setString(4, "completed");
                    ps.executeUpdate();
                }

                // Mark seat as unavailable
                try (PreparedStatement ps2 = c.prepareStatement(
                        "UPDATE seats SET is_available=0 WHERE showtime_id=? AND seat_label=?")) {
                    ps2.setInt(1, showtimeId);
                    ps2.setString(2, seat);
                    ps2.executeUpdate();
                }

                c.commit();
                JOptionPane.showMessageDialog(frame, "Επιτυχής κράτηση και πληρωμή!");
                cardLayout.show(mainPanel, "postLoginHome");

            } catch (SQLException ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(frame, "Σφάλμα κράτησης: " + ex.getMessage());
            }
        });

        panel.add(grid, BorderLayout.CENTER);
        JPanel bp = new JPanel();
        bp.add(btnBack);
        bp.add(btnBook);
        panel.add(bp, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildAdminMoviesPage() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        // Header
        JLabel titleLabel = new JLabel("Διαχείριση Ταινιών", SwingConstants.CENTER);
        titleLabel.setFont(new Font("Arial", Font.BOLD, 24));

        // Top panel with buttons
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        JButton btnAddMovie = new JButton("Προσθήκη Νέας Ταινίας");
        JButton btnRefresh = new JButton("Ανανέωση");
        JButton btnBack = new JButton("Επιστροφή");

        btnAddMovie.addActionListener(e -> showAddMovieDialog());
        btnRefresh.addActionListener(e -> {
            // Recreate and refresh the admin movies page
            JPanel fresh = buildAdminMoviesPage();
            mainPanel.add(fresh, "AdminMovies");
            cardLayout.show(mainPanel, "AdminMovies");
        });
        btnBack.addActionListener(e -> {
            cardLayout.show(mainPanel, "AdminHome");
        });

        topPanel.add(btnAddMovie);
        topPanel.add(btnRefresh);
        topPanel.add(btnBack);

        // Movies table
        String[] columnNames = {"ID", "Τίτλος", "Διάρκεια", "Αξιολόγηση", "Περιγραφή", "Ενέργειες"};
        DefaultTableModel tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 5; // Only actions column is editable
            }
        };

        JTable moviesTable = new JTable(tableModel);
        moviesTable.setRowHeight(40);
        moviesTable.getColumnModel().getColumn(5).setCellRenderer(new ButtonRenderer());
        moviesTable.getColumnModel().getColumn(5).setCellEditor(new ButtonEditor(new JCheckBox()));

        // Load movies data
        loadMoviesForAdmin(tableModel);

        JScrollPane scrollPane = new JScrollPane(moviesTable);

        panel.add(titleLabel, BorderLayout.NORTH);
        panel.add(topPanel, BorderLayout.PAGE_START);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel buildAdminBookingsPage() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        DefaultListModel<String> model = new DefaultListModel<>();
        JList<String> list = new JList<>(model);

        // Store booking IDs separately to match with list items
        List<Integer> bookingIds = new ArrayList<>();

        String sql = "SELECT u.username, u.email, b.id, m.title, s.show_time, b.seat_number, b.qr_code "
                + "FROM bookings b "
                + "JOIN users u ON b.user_id = u.id "
                + "JOIN showtimes s ON b.showtime_id=s.id "
                + "JOIN movies m ON s.movie_id=m.id "
                + "ORDER BY s.show_time";
        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            ResultSet rs = st.executeQuery();
            while (rs.next()) {
                int bookingId = rs.getInt("id");
                bookingIds.add(bookingId); // Store the booking ID

                LocalDateTime dt = rs.getTimestamp("show_time").toLocalDateTime();
                String day = capitalizeGreekDay(dt.getDayOfWeek());
                String lbl = String.format(
                        "%s (%s) %s %d/%d %02d:%02d - %s (Θέση %s)",
                        rs.getString("username"),
                        rs.getString("email"),
                        day,
                        dt.getDayOfMonth(), dt.getMonthValue(),
                        dt.getHour(), dt.getMinute(),
                        rs.getString("title"),
                        rs.getString("seat_number")
                );
                if (rs.getString("qr_code") != null) {
                    lbl += " [QR]";
                }
                model.addElement(lbl);
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }

        JButton btnBack = new JButton("Πίσω");
        btnBack.addActionListener(e -> cardLayout.show(mainPanel, "AdminHome"));

        JButton qrButton = new JButton("Προβολή QR Code");
        qrButton.addActionListener(e -> {
            int selectedIndex = list.getSelectedIndex();
            if (selectedIndex >= 0 && selectedIndex < bookingIds.size()) {
                int bookingId = bookingIds.get(selectedIndex);

                String qrSql = "SELECT qr_code FROM bookings WHERE id=?";
                try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(qrSql)) {
                    st.setInt(1, bookingId);
                    ResultSet rs = st.executeQuery();
                    if (rs.next()) {
                        String qrCode = rs.getString("qr_code");
                        if (qrCode != null && !qrCode.isEmpty()) {
                            showQRCodeDialog(qrCode);
                        } else {
                            JOptionPane.showMessageDialog(frame, "Δεν βρέθηκε QR code για αυτή την κράτηση");
                        }
                    }
                } catch (SQLException ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(frame, "Σφάλμα φόρτωσης QR code: " + ex.getMessage());
                }
            } else {
                JOptionPane.showMessageDialog(frame, "Παρακαλώ επιλέξτε μία κράτηση.");
            }
        });

        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        JButton deleteButton = new JButton("Διαγραφή Κράτησης");
        deleteButton.addActionListener(e -> {
            int selectedIndex = list.getSelectedIndex();
            if (selectedIndex >= 0 && selectedIndex < bookingIds.size()) {
                int bookingId = bookingIds.get(selectedIndex);

                int confirm = JOptionPane.showConfirmDialog(
                        frame,
                        "Είστε σίγουρος ότι θέλετε να διαγράψετε αυτή την κράτηση;",
                        "Επιβεβαίωση Διαγραφής",
                        JOptionPane.YES_NO_OPTION
                );

                if (confirm == JOptionPane.YES_OPTION) {
                    try (Connection c = createConnection()) {

                        // Βρες showtime_id και seat_number ΠΡΙΝ τη διαγραφή
                        int showtimeId = 0;
                        String seatLabel = "";

                        try (PreparedStatement ps = c.prepareStatement(
                                "SELECT showtime_id, seat_number FROM bookings WHERE id=?")) {
                            ps.setInt(1, bookingId);
                            ResultSet rs = ps.executeQuery();
                            if (rs.next()) {
                                showtimeId = rs.getInt("showtime_id");
                                seatLabel = rs.getString("seat_number");
                            }
                        }

                        // Διαγραφή κράτησης
                        String deleteSql = "DELETE FROM bookings WHERE id = ?";
                        try (PreparedStatement st = c.prepareStatement(deleteSql)) {
                            st.setInt(1, bookingId);
                            int affected = st.executeUpdate();
                            if (affected > 0) {
                                // Κάνε τη θέση ξανά διαθέσιμη
                                try (PreparedStatement ps2 = c.prepareStatement(
                                        "UPDATE seats SET is_available=1 WHERE showtime_id=? AND seat_label=?")) {
                                    ps2.setInt(1, showtimeId);
                                    ps2.setString(2, seatLabel);
                                    ps2.executeUpdate();
                                }

                                model.remove(selectedIndex);
                                bookingIds.remove(selectedIndex);
                                JOptionPane.showMessageDialog(frame, "Η κράτηση διαγράφηκε με επιτυχία.");
                            } else {
                                JOptionPane.showMessageDialog(frame, "Αποτυχία διαγραφής.");
                            }
                        }

                    } catch (SQLException ex) {
                        ex.printStackTrace();
                        JOptionPane.showMessageDialog(frame, "Σφάλμα κατά τη διαγραφή: " + ex.getMessage());
                    }
                }

            } else {
                JOptionPane.showMessageDialog(frame, "Παρακαλώ επιλέξτε μια κράτηση για διαγραφή.");
            }
        });

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        leftPanel.add(btnBack);
        leftPanel.add(qrButton);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        rightPanel.add(deleteButton);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(leftPanel, BorderLayout.WEST);
        bottomPanel.add(rightPanel, BorderLayout.EAST);

        panel.add(bottomPanel, BorderLayout.SOUTH);
        return panel;
    }

    private void showAddMovieDialog() {
        showMovieDialog(null, "Προσθήκη Νέας Ταινίας");
    }

    private void showEditMovieDialog(int movieId) {
        showMovieDialog(movieId, "Επεξεργασία Ταινίας");
    }

    private void showMovieDialog(Integer movieId, String title) {
        JDialog dialog = new JDialog(frame, title, true);
        dialog.setSize(500, 600);
        dialog.setLayout(new BorderLayout());

        JPanel formPanel = new JPanel(new GridLayout(7, 2, 10, 10)); // +1 για το image file
        formPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JTextField titleField = new JTextField();
        JTextField imageFileField = new JTextField(); // Για τοπικό αρχείο
        JButton btnBrowse = new JButton("Επιλογή...");
        JTextField durationField = new JTextField();
        JTextField starsField = new JTextField();
        JTextArea descArea = new JTextArea(5, 20);
        descArea.setWrapStyleWord(true);
        descArea.setLineWrap(true);
        JScrollPane descScroll = new JScrollPane(descArea);

        // Panel για image selection
        JPanel imagePanel = new JPanel(new BorderLayout());
        imagePanel.add(imageFileField, BorderLayout.CENTER);
        imagePanel.add(btnBrowse, BorderLayout.EAST);

        btnBrowse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Image files", "jpg", "jpeg", "png", "gif"));

            if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                imageFileField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        formPanel.add(new JLabel("Τίτλος:"));
        formPanel.add(titleField);
        formPanel.add(new JLabel("Εικόνα (τοπικό αρχείο):"));
        formPanel.add(imagePanel);
        formPanel.add(new JLabel("Διάρκεια:"));
        formPanel.add(durationField);
        formPanel.add(new JLabel("Αξιολόγηση:"));
        formPanel.add(starsField);
        formPanel.add(new JLabel("Περιγραφή:"));
        formPanel.add(descScroll);

        // Αν edit, φόρτωσε τα δεδομένα
        if (movieId != null) {
            loadMovieDataForEditLocal(movieId, titleField, durationField, starsField, descArea);        // Έλεγχος αν υπάρχει τοπική εικόνα
            File localImage = new File("src/main/resources/images/" + movieId + ".jpg");
            if (localImage.exists()) {
                imageFileField.setText(localImage.getAbsolutePath());
            }
        }

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton btnSave = new JButton(movieId == null ? "Προσθήκη" : "Ενημέρωση");
        JButton btnCancel = new JButton("Ακύρωση");

        btnSave.addActionListener(e -> {
            saveMovieWithImage(movieId, titleField.getText(), imageFileField.getText(),
                    durationField.getText(), starsField.getText(), descArea.getText());
            dialog.dispose();
            // Refresh admin movies page
            JPanel fresh = buildAdminMoviesPage();
            mainPanel.add(fresh, "AdminMovies");
            cardLayout.show(mainPanel, "AdminMovies");
        });

        btnCancel.addActionListener(e -> dialog.dispose());

        buttonPanel.add(btnSave);
        buttonPanel.add(btnCancel);

        dialog.add(formPanel, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private void showShowtimesManagementDialog(int movieId) {
        JDialog dialog = new JDialog(frame, "Διαχείριση Προβολών", true);
        dialog.setSize(600, 500);
        dialog.setLayout(new BorderLayout());

        String movieTitle = getMovieTitle(movieId);
        JLabel titleLabel = new JLabel("Προβολές για: " + movieTitle, SwingConstants.CENTER);
        titleLabel.setFont(new Font("Arial", Font.BOLD, 16));

        DefaultListModel<String> model = new DefaultListModel<>();
        JList<String> showtimesList = new JList<>(model);
        List<Integer> showtimeIds = new ArrayList<>();

        loadShowtimesForMovie(movieId, model, showtimeIds);

        JPanel buttonPanel = new JPanel(new GridLayout(2, 2, 10, 10));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JButton btnAddShowtime = new JButton("Προσθήκη Προβολής");
        JButton btnDeleteShowtime = new JButton("Διαγραφή Προβολής");
        JButton btnRefresh = new JButton("Ανανέωση");
        JButton btnClose = new JButton("Κλείσιμο");

        btnAddShowtime.addActionListener(e -> {
            showAddShowtimeDialog(movieId);
            dialog.dispose();
            showShowtimesManagementDialog(movieId);
        });

        btnDeleteShowtime.addActionListener(e -> {
            int selectedIndex = showtimesList.getSelectedIndex();
            if (selectedIndex >= 0 && selectedIndex < showtimeIds.size()) {
                int showtimeId = showtimeIds.get(selectedIndex);
                deleteShowtime(showtimeId);
                loadShowtimesForMovie(movieId, model, showtimeIds);
            } else {
                JOptionPane.showMessageDialog(dialog, "Επιλέξτε προβολή για διαγραφή");
            }
        });

        btnRefresh.addActionListener(e -> {
            loadShowtimesForMovie(movieId, model, showtimeIds);
        });

        btnClose.addActionListener(e -> dialog.dispose());

        buttonPanel.add(btnAddShowtime);
        buttonPanel.add(btnDeleteShowtime);
        buttonPanel.add(btnRefresh);
        buttonPanel.add(btnClose);

        dialog.add(titleLabel, BorderLayout.NORTH);
        dialog.add(new JScrollPane(showtimesList), BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private void showAddShowtimeDialog(int movieId) {
        JDialog dialog = new JDialog(frame, "Προσθήκη Προβολής", true);
        dialog.setSize(400, 300);
        dialog.setLayout(new BorderLayout());

        JPanel formPanel = new JPanel(new GridLayout(6, 2, 10, 10));
        formPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JTextField dayField = new JTextField("26");
        JTextField monthField = new JTextField("5");
        JTextField yearField = new JTextField("2025");
        JTextField hourField = new JTextField("19");
        JTextField minuteField = new JTextField("00");

        formPanel.add(new JLabel("Ημέρα:"));
        formPanel.add(dayField);
        formPanel.add(new JLabel("Μήνας:"));
        formPanel.add(monthField);
        formPanel.add(new JLabel("Έτος:"));
        formPanel.add(yearField);
        formPanel.add(new JLabel("Ώρα (HH):"));
        formPanel.add(hourField);
        formPanel.add(new JLabel("Λεπτά (MM):"));
        formPanel.add(minuteField);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton btnSave = new JButton("Αποθήκευση");
        JButton btnCancel = new JButton("Ακύρωση");

        btnSave.addActionListener(e -> {
            try {
                int day = Integer.parseInt(dayField.getText().trim());
                int month = Integer.parseInt(monthField.getText().trim());
                int year = Integer.parseInt(yearField.getText().trim());
                int hour = Integer.parseInt(hourField.getText().trim());
                int minute = Integer.parseInt(minuteField.getText().trim());

                if (addShowtime(movieId, day, month, year, hour, minute)) {
                    dialog.dispose();
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(dialog, "Παρακαλώ εισάγετε έγκυρους αριθμούς");
            }
        });

        btnCancel.addActionListener(e -> dialog.dispose());

        buttonPanel.add(btnSave);
        buttonPanel.add(btnCancel);

        dialog.add(formPanel, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private String getMovieTitle(int movieId) {
        String sql = "SELECT title FROM movies WHERE id = ?";
        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setInt(1, movieId);
            ResultSet rs = st.executeQuery();
            if (rs.next()) {
                return rs.getString("title");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return "Άγνωστη Ταινία";
    }

    private void loadShowtimesForMovie(int movieId, DefaultListModel<String> model, List<Integer> showtimeIds) {
        model.clear();
        showtimeIds.clear();

        String sql = "SELECT id, show_time FROM showtimes WHERE movie_id = ? ORDER BY show_time";
        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setInt(1, movieId);
            ResultSet rs = st.executeQuery();
            while (rs.next()) {
                int id = rs.getInt("id");
                LocalDateTime dt = rs.getTimestamp("show_time").toLocalDateTime();
                String day = capitalizeGreekDay(dt.getDayOfWeek());
                String label = String.format(
                        "%s %d/%d %02d:%02d (ID: %d)",
                        day,
                        dt.getDayOfMonth(), dt.getMonthValue(),
                        dt.getHour(), dt.getMinute(),
                        id
                );
                model.addElement(label);
                showtimeIds.add(id);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private boolean addShowtime(int movieId, int day, int month, int year, int hour, int minute) {
        if (day < 1 || day > 31 || month < 1 || month > 12
                || year < 2024 || hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            JOptionPane.showMessageDialog(frame, "Παρακαλώ εισάγετε έγκυρες τιμές");
            return false;
        }

        try {
            LocalDateTime showTime = LocalDateTime.of(year, month, day, hour, minute);

            String sql = "INSERT INTO showtimes (movie_id, show_time) VALUES (?, ?)";
            try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {

                st.setInt(1, movieId);
                st.setTimestamp(2, Timestamp.valueOf(showTime));

                int affectedRows = st.executeUpdate();
                if (affectedRows > 0) {
                    ResultSet generatedKeys = st.getGeneratedKeys();
                    if (generatedKeys.next()) {
                        int showtimeId = generatedKeys.getInt(1);
                        createSeatsForShowtime(showtimeId);
                        JOptionPane.showMessageDialog(frame, "Η προβολή προστέθηκε επιτυχώς!");
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Σφάλμα: " + e.getMessage());
        }
        return false;
    }

    private void createSeatsForShowtime(int showtimeId) throws SQLException {
        String sql = "INSERT INTO seats (showtime_id, seat_label, is_available) VALUES (?, ?, ?)";
        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {

            char[] rows = {'A', 'B', 'C'};
            for (char row : rows) {
                for (int seatNum = 1; seatNum <= 10; seatNum++) {
                    st.setInt(1, showtimeId);
                    st.setString(2, row + String.valueOf(seatNum));
                    st.setBoolean(3, true);
                    st.addBatch();
                }
            }
            st.executeBatch();
        }
    }

    private void deleteShowtime(int showtimeId) {
        int confirm = JOptionPane.showConfirmDialog(
                frame,
                "Θα διαγραφούν και όλες οι κρατήσεις και θέσεις!",
                "Επιβεβαίωση Διαγραφής",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );

        if (confirm == JOptionPane.YES_OPTION) {
            try (Connection c = createConnection()) {
                c.setAutoCommit(false);

                try {
                    // Delete bookings first
                    try (PreparedStatement st = c.prepareStatement("DELETE FROM bookings WHERE showtime_id = ?")) {
                        st.setInt(1, showtimeId);
                        st.executeUpdate();
                    }

                    // Delete seats
                    try (PreparedStatement st = c.prepareStatement("DELETE FROM seats WHERE showtime_id = ?")) {
                        st.setInt(1, showtimeId);
                        st.executeUpdate();
                    }

                    // Delete showtime
                    try (PreparedStatement st = c.prepareStatement("DELETE FROM showtimes WHERE id = ?")) {
                        st.setInt(1, showtimeId);
                        st.executeUpdate();
                    }

                    c.commit();
                    JOptionPane.showMessageDialog(frame, "Η προβολή διαγράφηκε επιτυχώς!");

                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(frame, "Σφάλμα: " + e.getMessage());
            }
        }
    }

    private void loadMovieDataForEdit(int movieId, JTextField titleField, JTextField urlField,
            JTextField durationField, JTextField starsField, JTextArea descArea) {
        String sql = "SELECT title, picture_url, duration, stars, dc FROM movies WHERE id = ?";

        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {

            st.setInt(1, movieId);
            ResultSet rs = st.executeQuery();
            if (rs.next()) {
                titleField.setText(rs.getString("title"));
                urlField.setText(rs.getString("picture_url"));
                durationField.setText(rs.getString("duration"));
                starsField.setText(rs.getString("stars"));
                descArea.setText(rs.getString("dc"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void loadMovieDataForEditLocal(int movieId, JTextField titleField,
            JTextField durationField, JTextField starsField,
            JTextArea descArea) {
        String sql = "SELECT title, duration, stars, dc FROM movies WHERE id = ?";

        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {

            st.setInt(1, movieId);
            ResultSet rs = st.executeQuery();
            if (rs.next()) {
                if (titleField != null) {
                    titleField.setText(rs.getString("title"));
                }
                if (durationField != null) {
                    durationField.setText(rs.getString("duration"));
                }
                if (starsField != null) {
                    starsField.setText(rs.getString("stars"));
                }
                if (descArea != null) {
                    descArea.setText(rs.getString("dc"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private boolean saveMovie(Integer movieId, String title, String pictureUrl,
            String duration, String stars, String description) {
        if (title.trim().isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Ο τίτλος είναι υποχρεωτικός");
            return false;
        }

        String sql;
        if (movieId == null) {
            sql = "INSERT INTO movies (title, picture_url, duration, stars, dc) VALUES (?, ?, ?, ?, ?)";
        } else {
            sql = "UPDATE movies SET title = ?, picture_url = ?, duration = ?, stars = ?, dc = ? WHERE id = ?";
        }

        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {

            st.setString(1, title);
            st.setString(2, pictureUrl.trim().isEmpty() ? null : pictureUrl);
            st.setString(3, duration.trim().isEmpty() ? null : duration);
            st.setString(4, stars.trim().isEmpty() ? null : stars);
            st.setString(5, description.trim().isEmpty() ? null : description);

            if (movieId != null) {
                st.setInt(6, movieId);
            }

            int affectedRows = st.executeUpdate();
            if (affectedRows > 0) {
                String message = movieId == null ? "Η ταινία προστέθηκε!" : "Η ταινία ενημερώθηκε!";
                JOptionPane.showMessageDialog(frame, message);
                return true;
            }

        } catch (SQLException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Σφάλμα: " + e.getMessage());
        }
        return false;
    }

    private void deleteMovie(int movieId) {
        int confirm = JOptionPane.showConfirmDialog(
                frame,
                "Θα διαγραφούν όλες οι προβολές και κρατήσεις!",
                "Επιβεβαίωση Διαγραφής",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );

        if (confirm == JOptionPane.YES_OPTION) {
            try (Connection c = createConnection()) {
                c.setAutoCommit(false);

                try {
                    // Delete in correct order
                    c.prepareStatement("DELETE FROM bookings WHERE showtime_id IN (SELECT id FROM showtimes WHERE movie_id = " + movieId + ")").executeUpdate();
                    c.prepareStatement("DELETE FROM seats WHERE showtime_id IN (SELECT id FROM showtimes WHERE movie_id = " + movieId + ")").executeUpdate();
                    c.prepareStatement("DELETE FROM showtimes WHERE movie_id = " + movieId).executeUpdate();
                    c.prepareStatement("DELETE FROM movies WHERE id = " + movieId).executeUpdate();

                    c.commit();
                    JOptionPane.showMessageDialog(frame, "Η ταινία διαγράφηκε!");

                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(frame, "Σφάλμα: " + e.getMessage());
            }
        }
    }

    private void loadMoviesForAdmin(DefaultTableModel tableModel) {
        tableModel.setRowCount(0);
        String sql = "SELECT id, title, duration, stars, dc FROM movies ORDER BY title";

        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql); ResultSet rs = st.executeQuery()) {

            while (rs.next()) {
                int id = rs.getInt("id");
                String title = rs.getString("title");
                String duration = rs.getString("duration");
                String stars = rs.getString("stars");
                String description = rs.getString("dc");

                String shortDesc = description != null && description.length() > 50
                        ? description.substring(0, 50) + "..."
                        : description;

                Object[] row = {id, title, duration, stars, shortDesc, "Ενέργειες"};
                tableModel.addRow(row);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ===============================
    // UTILITY METHODS
    // ===============================
    private Connection createConnection() throws SQLException {
        Dotenv d = Dotenv.load();
        return DriverManager.getConnection(
                d.get("DB_URL"),
                d.get("DB_USER"),
                d.get("PASSWORD")
        );
    }

    private String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String capitalizeGreekDay(DayOfWeek day) {
        switch (day) {
            case MONDAY:
                return "Δευτέρα";
            case TUESDAY:
                return "Τρίτη";
            case WEDNESDAY:
                return "Τετάρτη";
            case THURSDAY:
                return "Πέμπτη";
            case FRIDAY:
                return "Παρασκευή";
            case SATURDAY:
                return "Σάββατο";
            case SUNDAY:
                return "Κυριακή";
            default:
                return day.toString();
        }
    }

    private MovieItem createMovieItem(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        String title = rs.getString("title");
        String url = rs.getString("picture_url");
        String duration = rs.getString("duration");
        String stars = rs.getString("stars");

        ImageIcon icon;

        // Χρήση τοπικής εικόνας αν υπάρχει
        String localImagePath = "src/main/resources/images/" + id + ".jpg";
        File imageFile = new File(localImagePath);

        if (imageFile.exists()) {
            // Φόρτωση από τοπικό αρχείο
            icon = new ImageIcon(localImagePath);
            Image scaled = icon.getImage().getScaledInstance(80, 80, Image.SCALE_FAST);
            icon = new ImageIcon(scaled);
        } else {
            // Default placeholder εικόνα
            icon = new ImageIcon("src/main/resources/images/placeholder.jpg");
            if (icon.getImageLoadStatus() != MediaTracker.COMPLETE) {
                icon = new ImageIcon(); // Empty αν δεν υπάρχει ούτε το placeholder
            }
        }

        return new MovieItem(id, title, icon, duration, stars);
    }

    private String generateQRCodeImage(String text) {
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, 200, 200);

            BufferedImage qrImage = MatrixToImageWriter.toBufferedImage(bitMatrix);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(qrImage, "PNG", baos);

            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (WriterException | IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void showQRCodeDialog(String base64QR) {
        if (base64QR == null || base64QR.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Δεν υπάρχει διαθέσιμο QR code");
            return;
        }

        try {
            byte[] imageBytes = Base64.getDecoder().decode(base64QR);
            ImageIcon qrIcon = new ImageIcon(imageBytes);

            JDialog dialog = new JDialog(frame, "QR Code Εισιτηρίου", true);
            dialog.setSize(300, 330);
            dialog.setLayout(new BorderLayout());

            JLabel qrLabel = new JLabel(qrIcon);
            qrLabel.setHorizontalAlignment(JLabel.CENTER);
            dialog.add(qrLabel, BorderLayout.CENTER);

            JButton closeBtn = new JButton("Κλείσιμο");
            closeBtn.addActionListener(e -> dialog.dispose());
            JPanel buttonPanel = new JPanel();
            buttonPanel.add(closeBtn);
            dialog.add(buttonPanel, BorderLayout.SOUTH);

            dialog.setLocationRelativeTo(frame);
            dialog.setVisible(true);
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(frame, "Σφάλμα φόρτωσης QR code");
        }
    }

    private String getMovieInfoForShowtime(int showtimeId) {
        String sql = """
            SELECT m.title, s.show_time 
            FROM showtimes s 
            JOIN movies m ON s.movie_id = m.id 
            WHERE s.id = ?
        """;

        try (Connection c = createConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setInt(1, showtimeId);
            ResultSet rs = st.executeQuery();

            if (rs.next()) {
                String title = rs.getString("title");
                Timestamp showTime = rs.getTimestamp("show_time");
                return String.format("%s (%s)",
                        title,
                        showTime.toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM HH:mm")));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // Include the existing buildShowtimesPage, buildSeatsPage, buildAdminMoviesPage, 
    // buildAdminBookingsPage methods from the original code here...
    // [These remain unchanged from the original implementation]
    // ===============================
    // INNER CLASSES
    // ===============================
    private static class MovieItem {

        private final int id;
        private final String title;
        private final ImageIcon icon;
        private final String duration;
        private final String stars;
        private boolean isFavorite;

        public MovieItem(int id, String title, ImageIcon icon, String duration, String stars) {
            this.id = id;
            this.title = title;
            this.icon = icon;
            this.duration = duration;
            this.stars = stars;
            this.isFavorite = false;
        }

        public int getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public ImageIcon getIcon() {
            return icon;
        }

        public String getDuration() {
            return duration;
        }

        public String getStars() {
            return stars;
        }

        public boolean isFavorite() {
            return isFavorite;
        }

        public void setFavorite(boolean favorite) {
            this.isFavorite = favorite;
        }

        @Override
        public String toString() {
            return title + " (" + duration + ", " + stars + "★)" + (isFavorite ? " ❤" : "");
        }
    }

    class ButtonRenderer extends JButton implements TableCellRenderer {

        public ButtonRenderer() {
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            setText("Ενέργειες");
            return this;
        }
    }

// Button editor for the actions column
    class ButtonEditor extends DefaultCellEditor {

        protected JButton button;
        private String label;
        private boolean isPushed;
        private JTable table;
        private int selectedRow;

        public ButtonEditor(JCheckBox checkBox) {
            super(checkBox);
            button = new JButton();
            button.setOpaque(true);
            button.addActionListener(e -> fireEditingStopped());
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value,
                boolean isSelected, int row, int column) {
            this.table = table;
            this.selectedRow = row;
            label = (value == null) ? "Ενέργειες" : value.toString();
            button.setText(label);
            isPushed = true;
            return button;
        }

        @Override
        public Object getCellEditorValue() {
            if (isPushed) {
                // Show popup menu with actions
                JPopupMenu popup = new JPopupMenu();

                JMenuItem editItem = new JMenuItem("Επεξεργασία");
                JMenuItem deleteItem = new JMenuItem("Διαγραφή");
                JMenuItem showtimesItem = new JMenuItem("Διαχείριση Προβολών");

                int movieId = (Integer) table.getValueAt(selectedRow, 0);

                editItem.addActionListener(e -> showEditMovieDialog(movieId));
                deleteItem.addActionListener(e -> deleteMovie(movieId));
                showtimesItem.addActionListener(e -> showShowtimesManagementDialog(movieId));

                popup.add(editItem);
                popup.add(deleteItem);
                popup.add(showtimesItem);

                // Show popup at button location
                popup.show(button, 0, button.getHeight());
            }
            isPushed = false;
            return label;
        }

        @Override
        public boolean stopCellEditing() {
            isPushed = false;
            return super.stopCellEditing();
        }
    }

    private static class MovieCellRenderer extends JPanel implements ListCellRenderer<MovieItem> {

        private final JLabel imgLabel = new JLabel();
        private final JLabel textLabel = new JLabel();
        private final JLabel favLabel = new JLabel();

        public MovieCellRenderer() {
            setLayout(new BorderLayout(5, 5));
            setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

            textLabel.setFont(textLabel.getFont().deriveFont(Font.BOLD, 14f));
            favLabel.setFont(favLabel.getFont().deriveFont(16f));
            favLabel.setForeground(Color.RED);

            add(imgLabel, BorderLayout.WEST);
            add(textLabel, BorderLayout.CENTER);
            add(favLabel, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends MovieItem> list,
                MovieItem item, int index,
                boolean isSelected, boolean cellHasFocus) {
            imgLabel.setIcon(item.getIcon());
            textLabel.setText(String.format("<html>%s<br>Διάρκεια: %s | Αξιολόγηση: %s★</html>",
                    item.getTitle(), item.getDuration(), item.getStars()));
            favLabel.setText(item.isFavorite() ? "❤" : "");

            if (isSelected) {
                setBackground(list.getSelectionBackground());
                setForeground(list.getSelectionForeground());
            } else {
                setBackground(list.getBackground());
                setForeground(list.getForeground());
            }

            setOpaque(true);
            return this;
        }
    }
}
