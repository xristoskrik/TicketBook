package com.uniwa.ticketbook;
import io.github.cdimascio.dotenv.Dotenv;
import javax.swing.*;
import java.awt.*;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;

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
        frame.setSize(400, 300);

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

   public class MovieItem {
    private String title;
    private ImageIcon icon;
    private String duration;
    private String stars;

    public MovieItem(String title, ImageIcon icon, String duration, String stars) {
        this.title = title;
        this.icon = icon;
        this.duration = duration;
        this.stars = stars;
    }

    // getters
    public String getTitle() { return title; }
    public ImageIcon getIcon() { return icon; }
    public String getDuration() { return duration; }
    public String getStars() { return stars; }
}

class MovieCellRenderer extends JPanel implements ListCellRenderer<MovieItem> {
    private final JLabel imageLabel = new JLabel();
    private final JLabel titleLabel = new JLabel();
    private final JLabel detailsLabel = new JLabel();

    public MovieCellRenderer() {
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Panel for title and details stacked vertically
        JPanel textPanel = new JPanel(new GridLayout(0, 1));
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
        textPanel.add(titleLabel);
        textPanel.add(detailsLabel);

        add(imageLabel, BorderLayout.WEST);
        add(textPanel, BorderLayout.CENTER);
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends MovieItem> list,
                                                  MovieItem item, int index,
                                                  boolean isSelected, boolean cellHasFocus) {
        imageLabel.setIcon(item.getIcon());
        titleLabel.setText(item.getTitle());
        detailsLabel.setText("Duration: " + item.getDuration() + " min | Stars: " + item.getStars());

        if (isSelected) {
            setBackground(list.getSelectionBackground());
            titleLabel.setForeground(list.getSelectionForeground());
            detailsLabel.setForeground(list.getSelectionForeground());
        } else {
            setBackground(list.getBackground());
            titleLabel.setForeground(list.getForeground());
            detailsLabel.setForeground(list.getForeground());
        }

        setOpaque(true);
        return this;
    }
}


    private JPanel buildRegisterPage() {
        JPanel panel = new JPanel(new GridLayout(7, 1, 10, 10));
        JTextField usernameField = new JTextField();
        JTextField emailField = new JTextField();
        JPasswordField passwordField = new JPasswordField();
        JButton registerBtn = new JButton("Register");

        panel.setBorder(BorderFactory.createEmptyBorder(20, 50, 20, 50));

        panel.add(new JLabel("Username:"));
        panel.add(usernameField);
        panel.add(new JLabel("Email:"));
        panel.add(emailField);
        panel.add(new JLabel("Password:"));
        panel.add(passwordField);
        panel.add(registerBtn);

        registerBtn.addActionListener(e -> {
            String username = usernameField.getText().trim();
            String email = emailField.getText().trim();
            String password = new String(passwordField.getPassword()).trim();

            if (username.isEmpty() || email.isEmpty() || password.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "All fields must be filled!");
                return;
            }

            // Hash the password before storing
            String hashedPassword = hashPassword(password);
            if (hashedPassword == null) {
                JOptionPane.showMessageDialog(frame, "Error hashing password!");
                return;
            }

               Dotenv dotenv = Dotenv.load();
                String url = dotenv.get("DB_URL");
                String user = dotenv.get("USERNAME");
                String dbPassword = dotenv.get("PASSWORD");

            String checkSql = "SELECT COUNT(*) FROM users WHERE username = ? OR email = ?";
            String insertSql = "INSERT INTO users (username, email, password, role) VALUES (?, ?, ?, ?)";

            try (Connection conn = DriverManager.getConnection(url, user, dbPassword);
                 PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {

                // Check if username or email already exists
                checkStmt.setString(1, username);
                checkStmt.setString(2, email);
                ResultSet rs = checkStmt.executeQuery();
                rs.next();
                int count = rs.getInt(1);

                if (count > 0) {
                    JOptionPane.showMessageDialog(frame, "Username or Email already exists!");
                    return;
                }

                // Insert new user
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    insertStmt.setString(1, username);
                    insertStmt.setString(2, email);
                    insertStmt.setString(3, hashedPassword);
                    insertStmt.setString(4, "user");

                    int rowsInserted = insertStmt.executeUpdate();
                    if (rowsInserted > 0) {
                        JOptionPane.showMessageDialog(frame, "Registered successfully! Returning to home.");
                        usernameField.setText("");
                        emailField.setText("");
                        passwordField.setText("");
                        cardLayout.show(mainPanel, "home");
                    } else {
                        JOptionPane.showMessageDialog(frame, "Registration failed. Try again.");
                    }
                }

            } catch (SQLException ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(frame, "Error during registration: " + ex.getMessage());
            }
        });

        return panel;
    }

    private JPanel buildLoginPage() {
        JPanel panel = new JPanel(new GridLayout(6, 1, 10, 10));
        JTextField userOrEmailField = new JTextField();
        JPasswordField passwordField = new JPasswordField();
        JButton loginBtn = new JButton("Login");
        JLabel messageLabel = new JLabel("", SwingConstants.CENTER);

        panel.setBorder(BorderFactory.createEmptyBorder(20, 50, 20, 50));

        panel.add(new JLabel("Username or Email:"));
        panel.add(userOrEmailField);
        panel.add(new JLabel("Password:"));
        panel.add(passwordField);
        panel.add(loginBtn);
        panel.add(messageLabel);

        loginBtn.addActionListener(e -> {
            String userOrEmail = userOrEmailField.getText().trim();
            String password = new String(passwordField.getPassword()).trim();

            if (userOrEmail.isEmpty() || password.isEmpty()) {
                messageLabel.setText("Please enter username/email and password.");
                return;
            }

            // Hash input password to compare with DB stored hash
            String hashedPassword = hashPassword(password);
            if (hashedPassword == null) {
                messageLabel.setText("Error processing password.");
                return;
            }

               Dotenv dotenv = Dotenv.load();
        String url = dotenv.get("DB_URL");
        String user = dotenv.get("USERNAME");
        String dbPassword = dotenv.get("PASSWORD");

            String sql = "SELECT username FROM users WHERE (username = ? OR email = ?) AND password = ?";

            try (Connection conn = DriverManager.getConnection(url, user, dbPassword);
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, userOrEmail);
                stmt.setString(2, userOrEmail);
                stmt.setString(3, hashedPassword);

                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    String username = rs.getString("username");
                    // Remove existing movies panel if present to avoid duplicates
                    if (mainPanel.getComponentCount() > 3) {
                        mainPanel.remove(mainPanel.getComponentCount() - 1);
                    }
                    mainPanel.add(buildMoviesPage(username), "movies");
                    cardLayout.show(mainPanel, "movies");
                    messageLabel.setText("");
                    userOrEmailField.setText("");
                    passwordField.setText("");
                } else {
                    messageLabel.setText("❌ Invalid credentials.");
                }

            } catch (SQLException ex) {
                ex.printStackTrace();
                messageLabel.setText("Error: " + ex.getMessage());
            }
        });

        return panel;
    }

   private JPanel buildMoviesPage(String username) {
    JPanel panel = new JPanel(new BorderLayout(10, 10));
    panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

    JLabel welcomeLabel = new JLabel("Welcome, " + username + "! Here are the movies:", SwingConstants.CENTER);
    welcomeLabel.setFont(new Font("Arial", Font.BOLD, 18));

    JTextField searchField = new JTextField();
    searchField.setToolTipText("Type to search movies by title");

    DefaultListModel<MovieItem> movieListModel = new DefaultListModel<>();
    JList<MovieItem> movieList = new JList<>(movieListModel);
    movieList.setCellRenderer(new MovieCellRenderer());
    movieList.setFixedCellHeight(300); // enough room for image and title
    movieList.setFixedCellWidth(300);
    movieList.setLayoutOrientation(JList.HORIZONTAL_WRAP);
    movieList.setVisibleRowCount(0); // enables wrapping

    JScrollPane scrollPane = new JScrollPane(movieList);

    JButton logoutBtn = new JButton("Logout");

    JPanel topPanel = new JPanel(new BorderLayout());
    topPanel.add(welcomeLabel, BorderLayout.NORTH);
    topPanel.add(searchField, BorderLayout.SOUTH);

    panel.add(topPanel, BorderLayout.NORTH);
    panel.add(scrollPane, BorderLayout.CENTER);
    panel.add(logoutBtn, BorderLayout.SOUTH);

    loadMovies(movieListModel, ""); // load all movies

    // Live search
    searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
        public void changedUpdate(javax.swing.event.DocumentEvent e) { updateList(); }
        public void removeUpdate(javax.swing.event.DocumentEvent e) { updateList(); }
        public void insertUpdate(javax.swing.event.DocumentEvent e) { updateList(); }

        private void updateList() {
            String filterText = searchField.getText().trim();
            loadMovies(movieListModel, filterText);
        }
    });

    logoutBtn.addActionListener(e -> cardLayout.show(mainPanel, "home"));

    return panel;
}

    private void loadMovies(DefaultListModel<MovieItem> model, String filter) {
    model.clear();
    Dotenv dotenv = Dotenv.load();
    String url = dotenv.get("DB_URL");
    String user = dotenv.get("USERNAME");
    String dbPassword = dotenv.get("PASSWORD");

    String sql = "SELECT title, picture_url,duration,stars FROM movies WHERE title LIKE ? ORDER BY title";

    try (Connection conn = DriverManager.getConnection(url, user, dbPassword);
         PreparedStatement stmt = conn.prepareStatement(sql)) {

        stmt.setString(1, "%" + filter + "%");
        ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
            String title = rs.getString("title");
            String imageUrlStr = rs.getString("picture_url");
            String duration = rs.getString("duration");
            String stars = rs.getString("stars");

            ImageIcon icon;
            try {
                URL imageUrl = new URL(imageUrlStr);
                ImageIcon rawIcon = new ImageIcon(imageUrl);
                Image scaledImage = rawIcon.getImage().getScaledInstance(80, 80, Image.SCALE_SMOOTH);
                icon = new ImageIcon(scaledImage);
            } catch (Exception ex) {
                ex.printStackTrace();
                icon = new ImageIcon();
            }

    model.addElement(new MovieItem(title, icon, duration, stars));
}


    } catch (SQLException e) {
        e.printStackTrace();
        JOptionPane.showMessageDialog(frame, "Error loading movies: " + e.getMessage());
    }
}

    private String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashedBytes = md.digest(password.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hashedBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }
}
