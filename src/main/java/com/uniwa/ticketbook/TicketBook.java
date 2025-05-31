package com.uniwa.ticketbook;

import io.github.cdimascio.dotenv.Dotenv;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import java.util.Base64;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class TicketBook {
    private JFrame frame;
    private CardLayout cardLayout;
    private JPanel mainPanel;
    private int currentUserId = -1;
    private String currentUsername;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new TicketBook().startApp());
    }

    private void startApp() {
        frame = new JFrame("TicketBook");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(800, 600);

        cardLayout = new CardLayout();
        mainPanel = new JPanel(cardLayout);

        // Προ-Login panels
        mainPanel.add(buildHomePage(),     "home");
        mainPanel.add(buildRegisterPage(), "register");
        mainPanel.add(buildLoginPage(),    "login");

        frame.getContentPane().add(mainPanel);
        cardLayout.show(mainPanel, "home");
        frame.setVisible(true);
    }

    // Αρχική πριν το login
    private JPanel buildHomePage() {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Καλωσορίσατε στην Εφαρμογή", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 24));
        JPanel buttons = new JPanel();
        JButton btnRegister = new JButton("Εγγραφή");
        JButton btnLogin    = new JButton("Είσοδος");
        btnRegister.addActionListener(e -> cardLayout.show(mainPanel, "register"));
        btnLogin   .addActionListener(e -> cardLayout.show(mainPanel, "login"));
        buttons.add(btnRegister);
        buttons.add(btnLogin);
        panel.add(title,   BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    // Registration panel
    private JPanel buildRegisterPage() {
    JPanel panel = new JPanel(new GridLayout(8,1,10,10));
    panel.setBorder(BorderFactory.createEmptyBorder(20,50,20,50));
    JTextField usernameField = new JTextField();
    JTextField emailField    = new JTextField();
    JPasswordField pwdField  = new JPasswordField();
    JButton btn = new JButton("Εγγραφή");
    JButton btnBack = new JButton("Πίσω");
    
    panel.add(new JLabel("Όνομα Χρήστη:"));
    panel.add(usernameField);
    panel.add(new JLabel("Email:"));
    panel.add(emailField);
    panel.add(new JLabel("Κωδικός:"));
    panel.add(pwdField);
    panel.add(btn);
    panel.add(btnBack);

    btn.addActionListener(e -> {
        String u = usernameField.getText().trim();
        String m = emailField.getText().trim();
        String pw = new String(pwdField.getPassword()).trim();
        if (u.isEmpty() || m.isEmpty() || pw.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Απαιτούνται όλα τα πεδία");
            return;
        }
        String hashed = hashPassword(pw);
        if (hashed == null) {
            JOptionPane.showMessageDialog(frame, "Hash error");
            return;
        }
        String checkSql = "SELECT COUNT(*) FROM users WHERE username=? OR email=?";
        String insertSql= "INSERT INTO users(username,email,password,role) VALUES(?,?,?,?)";
        try (Connection c = createConnection();
             PreparedStatement st = c.prepareStatement(checkSql)) {
            st.setString(1,u);
            st.setString(2,m);
            ResultSet rs = st.executeQuery(); rs.next();
            if (rs.getInt(1) > 0) {
                JOptionPane.showMessageDialog(frame, "Το όνομα χρήστη ή το Email υπάχει ήδη");
                return;
            }
            try (PreparedStatement st2 = c.prepareStatement(insertSql)) {
                st2.setString(1,u);
                st2.setString(2,m);
                st2.setString(3,hashed);
                st2.setString(4,"user");
                st2.executeUpdate();
            }
            JOptionPane.showMessageDialog(frame, "Επιτυχής Εγγραφή!");
            usernameField.setText("");
            emailField.setText("");
            pwdField.setText("");
            cardLayout.show(mainPanel, "home");
        } catch (SQLException ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Πρόβλημα κατά την εγγραφή "+ex.getMessage());
        }
    });

    btnBack.addActionListener(e -> cardLayout.show(mainPanel, "home"));

    return panel;
}

    // Login panel
   private JPanel buildLoginPage() {
    JPanel panel = new JPanel(new GridLayout(6,1,10,10));
    panel.setBorder(BorderFactory.createEmptyBorder(20,50,20,50));
    JTextField   ueField = new JTextField();
    JPasswordField pwField = new JPasswordField();
    JButton btn = new JButton("Είσοδος");
    JButton btnBack = new JButton("Πίσω");
    JLabel msg = new JLabel("", SwingConstants.CENTER);
    
    // Create button panel for both buttons
    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
    buttonPanel.add(btnBack);
    buttonPanel.add(btn);
    
    
    panel.add(new JLabel("Όνομα Χρήστη ή Mail:"));
    panel.add(ueField);
    panel.add(new JLabel("Κωδικός:"));
    panel.add(pwField);
    panel.add(buttonPanel);
    panel.add(msg);

    // Login action - extracted to reusable method
    Runnable loginAction = () -> {
    String ue = ueField.getText().trim();
    String pw = new String(pwField.getPassword()).trim();
    if (ue.isEmpty() || pw.isEmpty()) {
        msg.setText("Συμπλήρωσε τα στοιχεία σου");
        return;
    }
    String hashed = hashPassword(pw);
    if (hashed == null) {
        msg.setText("Hash error");
        return;
    }
    // Modified SQL to also select the role
    String sql = "SELECT id,username,role FROM users WHERE (username=? OR email=?) AND password=?";
    try (Connection c = createConnection();
         PreparedStatement st = c.prepareStatement(sql)) {
        st.setString(1,ue);
        st.setString(2,ue);
        st.setString(3,hashed);
        ResultSet rs = st.executeQuery();
        if (rs.next()) {
            String userRole = rs.getString("role");
            currentUserId   = rs.getInt("id");
            currentUsername = rs.getString("username");
            
            // Check role and redirect accordingly
            if ("user".equals(userRole)) {
                // Regular user login - show movie booking interface
                mainPanel.add(buildPostLoginHome(),   "postLoginHome");
                mainPanel.add(buildMoviesPage(),      "movies");
                cardLayout.show(mainPanel, "postLoginHome");
            } /*else if ("admin".equals(userRole)) {
                 Admin login
                
            }*/ else {
                // Invalid role
                msg.setText("Δεν έχετε δικαίωμα πρόσβασης σε αυτή την εφαρμογή");
                currentUserId = -1;
                currentUsername = null;
                return;
            }
        } else {
            msg.setText("Invalid credentials");
        }
    } catch (SQLException ex) {
        ex.printStackTrace();
        msg.setText("Login error: "+ex.getMessage());
    }
};

    // Button click listener
    btn.addActionListener(e -> loginAction.run());

    // Enter key listeners for both fields
    ueField.addKeyListener(new java.awt.event.KeyAdapter() {
        @Override
        public void keyPressed(java.awt.event.KeyEvent e) {
            if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER) {
                loginAction.run();
            }
        }
    });

    pwField.addKeyListener(new java.awt.event.KeyAdapter() {
        @Override
        public void keyPressed(java.awt.event.KeyEvent e) {
            if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER) {
                loginAction.run();
            }
        }
    });

    btnBack.addActionListener(e -> cardLayout.show(mainPanel, "home"));

    return panel;
}

    // Post Login 
    private JPanel buildPostLoginHome() {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Αρχική", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 24));
        
        // Κύριο πάνελ με τα κουμπιά λειτουργιών
        JPanel buttons = new JPanel();
        JButton btnSearch = new JButton("Αναζήτηση");
        JButton btnMyBkgs = new JButton("Οι κρατήσεις μου");
        btnSearch.addActionListener(e -> cardLayout.show(mainPanel, "movies"));
        btnMyBkgs.addActionListener(e -> {
            JPanel fresh = buildMyBookingsPage();
            mainPanel.add(fresh, "myBookings");
            cardLayout.show(mainPanel, "myBookings");
        });
        buttons.add(btnSearch);
        buttons.add(btnMyBkgs);
        
        // Πάνελ με το κουμπί αποσύνδεσης
        JPanel logoutPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        JButton btnLogout = new JButton("Αποσύνδεση");
        btnLogout.addActionListener(e -> {
            currentUserId = -1;
            currentUsername = null;
            cardLayout.show(mainPanel, "home");
        });
        logoutPanel.add(btnLogout);
        
        // Διαμόρφωση του κύριου πάνελ
        panel.add(title, BorderLayout.NORTH);
        panel.add(buttons, BorderLayout.CENTER);
        panel.add(logoutPanel, BorderLayout.SOUTH);
        
        return panel;
    }

    // Movies search & display
    private JPanel buildMoviesPage() {
        JPanel panel = new JPanel(new BorderLayout(10,10));
        panel.setBorder(BorderFactory.createEmptyBorder(20,20,20,20));
        JLabel welcome = new JLabel("Καλώς ήρθες, "+currentUsername, SwingConstants.CENTER);
        JTextField search = new JTextField();
        DefaultListModel<MovieItem> model = new DefaultListModel<>();
        JList<MovieItem> list = new JList<>(model);
        list.setCellRenderer(new MovieCellRenderer());
        list.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        list.setVisibleRowCount(0);

        loadMovies(model, "");
        search.getDocument().addDocumentListener(new DocumentListener(){
            public void insertUpdate(DocumentEvent e){ update(); }
            public void removeUpdate(DocumentEvent e){ update(); }
            public void changedUpdate(DocumentEvent e){ update(); }
            private void update(){
                loadMovies(model, search.getText().trim());
            }
        });

        JButton btnBack = new JButton("Πίσω");
        JButton btnNext = new JButton("Επόμενο");
        btnBack.addActionListener(e -> cardLayout.show(mainPanel, "postLoginHome"));
        btnNext.addActionListener(e -> {
            MovieItem mi = list.getSelectedValue();
            if (mi == null) {
                JOptionPane.showMessageDialog(frame, "Επίλεξε ταινία");
                return;
            }
            mainPanel.add(buildShowtimesPage(mi.getId()), "showtimes");
            cardLayout.show(mainPanel, "showtimes");
        });

        JPanel top = new JPanel(new BorderLayout(5,5));
        top.add(welcome, BorderLayout.NORTH);
        top.add(search,  BorderLayout.SOUTH);

        JPanel bot = new JPanel();
        bot.add(btnBack);
        bot.add(btnNext);

        panel.add(top,    BorderLayout.NORTH);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        panel.add(bot,    BorderLayout.SOUTH);
        return panel;
    }

    private void loadMovies(DefaultListModel<MovieItem> model, String filter) {
        model.clear();
        String sql = "SELECT id,title,picture_url,duration,stars FROM movies WHERE title LIKE ? ORDER BY title";
        try (Connection c = createConnection();
             PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, "%" + filter + "%");
            ResultSet rs = st.executeQuery();
            while (rs.next()) {
                int    id     = rs.getInt("id");
                String t      = rs.getString("title");
                String url    = rs.getString("picture_url");
                String dur    = rs.getString("duration");
                String stars  = rs.getString("stars");
                ImageIcon icon;
                try {
                    URL u = new URL(url);
                    icon = new ImageIcon(u);
                    Image img = icon.getImage().getScaledInstance(80,80,Image.SCALE_SMOOTH);
                    icon = new ImageIcon(img);
                } catch (Exception ex) {
                    icon = new ImageIcon();
                }
                model.addElement(new MovieItem(id, t, icon, dur, stars));
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Error loading movies");
        }
    }

    // Showtimes list
// Showtimes list with movie info panel
private JPanel buildShowtimesPage(int movieId) {
    JPanel pagePanel = new JPanel(new BorderLayout(10,10));
    pagePanel.setBorder(BorderFactory.createEmptyBorder(20,20,20,20));
    
    // Left side - showtimes list
    JPanel leftPanel = new JPanel(new BorderLayout(10,10));
    DefaultListModel<String> model = new DefaultListModel<>();
    JList<String> list = new JList<>(model);
    
    // Load showtimes
    String sql = "SELECT id,show_time FROM showtimes WHERE movie_id=? ORDER BY show_time";
    try (Connection c = createConnection();
         PreparedStatement st = c.prepareStatement(sql)) {
        st.setInt(1, movieId);
        ResultSet rs = st.executeQuery();
        while (rs.next()) {
            int sid = rs.getInt("id");
            LocalDateTime dt = rs.getTimestamp("show_time").toLocalDateTime();
            String day = capitalizeGreekDay(dt.getDayOfWeek());
            String label = String.format(
              "%s %d/%d %02d:%02d | %d",
              day,
              dt.getDayOfMonth(), dt.getMonthValue(),
              dt.getHour(),    dt.getMinute(),
              sid
            );
            model.addElement(label);
        }
    } catch (SQLException ex) {
        ex.printStackTrace();
    }
    
    leftPanel.add(new JLabel("Διαθέσιμες προβολές:"), BorderLayout.NORTH);
    leftPanel.add(new JScrollPane(list), BorderLayout.CENTER);
    
    // Right side - movie info panel
    JPanel rightPanel = new JPanel(new BorderLayout(10,10));
    rightPanel.setPreferredSize(new Dimension(300, 0)); // Fixed width for right panel
    rightPanel.setBorder(BorderFactory.createTitledBorder("Πληροφορίες Ταινίας"));
    
    // Load movie information
    JLabel movieImageLabel = new JLabel();
    JLabel movieTitleLabel = new JLabel();
    JTextArea movieDescArea = new JTextArea();
    
    movieTitleLabel.setFont(new Font("Arial", Font.BOLD, 16));
    movieTitleLabel.setHorizontalAlignment(SwingConstants.CENTER);
    
    movieDescArea.setWrapStyleWord(true);
    movieDescArea.setLineWrap(true);
    movieDescArea.setEditable(false);
    movieDescArea.setOpaque(false);
    movieDescArea.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
    
    // Query to get movie details including description
    String movieSql = "SELECT title, picture_url, dc FROM movies WHERE id=?";
    try (Connection c = createConnection();
         PreparedStatement st = c.prepareStatement(movieSql)) {
        st.setInt(1, movieId);
        ResultSet rs = st.executeQuery();
        if (rs.next()) {
            String title = rs.getString("title");
            String pictureUrl = rs.getString("picture_url");
            String description = rs.getString("dc");
            
            // Set title
            movieTitleLabel.setText(title);
            
            // Set description
            if (description != null && !description.trim().isEmpty()) {
                movieDescArea.setText(description);
            } else {
                movieDescArea.setText("Δεν υπάρχει διαθέσιμη περιγραφή για αυτή την ταινία.");
            }
            
            // Load and set movie image
            try {
                URL imageUrl = new URL(pictureUrl);
                ImageIcon originalIcon = new ImageIcon(imageUrl);
                // Scale image to fit nicely in the panel 
                Image scaledImage = originalIcon.getImage().getScaledInstance(200,250 , Image.SCALE_SMOOTH);
                ImageIcon scaledIcon = new ImageIcon(scaledImage);
                movieImageLabel.setIcon(scaledIcon);
                movieImageLabel.setHorizontalAlignment(SwingConstants.CENTER);
            } catch (Exception ex) {
                // If image loading fails, show placeholder
                movieImageLabel.setText("Δεν είναι διαθέσιμη εικόνα");
                movieImageLabel.setHorizontalAlignment(SwingConstants.CENTER);
                movieImageLabel.setBorder(BorderFactory.createLineBorder(Color.GRAY));
                movieImageLabel.setPreferredSize(new Dimension(100, 150));
            }
        }
    } catch (SQLException ex) {
        ex.printStackTrace();
        movieTitleLabel.setText("Σφάλμα φόρτωσης τίτλου");
        movieDescArea.setText("Σφάλμα φόρτωσης περιγραφής ταινίας.");
    }
    
    // Arrange right panel components
    rightPanel.add(movieImageLabel, BorderLayout.NORTH);
    rightPanel.add(movieTitleLabel, BorderLayout.CENTER);
    rightPanel.add(new JScrollPane(movieDescArea), BorderLayout.SOUTH);
    
    // Buttons
    JButton btnBack = new JButton("Πίσω");
    JButton btnNext = new JButton("Επόμενο");
    btnBack.addActionListener(e -> cardLayout.show(mainPanel, "movies"));
    btnNext.addActionListener(e -> {
        String sel = list.getSelectedValue();
        if (sel == null) {
            JOptionPane.showMessageDialog(frame, "Επίλεξε προβολή");
            return;
        }
        int idx = sel.lastIndexOf('|');
        int sid = Integer.parseInt(sel.substring(idx+1).trim());
        mainPanel.add(buildSeatsPage(sid), "seats");
        cardLayout.show(mainPanel, "seats");
    });

    JPanel buttonPanel = new JPanel();
    buttonPanel.add(btnBack);
    buttonPanel.add(btnNext);
    
    // Arrange main layout
    pagePanel.add(leftPanel, BorderLayout.CENTER);
    pagePanel.add(rightPanel, BorderLayout.EAST);
    pagePanel.add(buttonPanel, BorderLayout.SOUTH);
    
    return pagePanel;
}

    // Seats selection with QR code generation
    // Replace the existing buildSeatsPage method with this corrected version:

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
    

    // Grid 3 x 10
    JPanel grid = new JPanel(new GridLayout(3,10,5,5));
    List<JButton> buttons = new ArrayList<>();
    
    // Updated SQL query to properly order seats: A1-A10, B1-B10, C1-C10
    String sql = "SELECT seat_label,is_available FROM seats " +
                 "WHERE showtime_id=? ORDER BY " +
                 "SUBSTRING(seat_label,1,1), " +  // First by letter (A, B, C)
                 "CAST(SUBSTRING(seat_label,2) AS UNSIGNED)";  // Then by number (1-10)
    
    try (Connection c = createConnection();
         PreparedStatement st = c.prepareStatement(sql)) {
        st.setInt(1, showtimeId);
        ResultSet rs = st.executeQuery();
        while (rs.next()) {
            String sl = rs.getString("seat_label");
            boolean av = rs.getBoolean("is_available");
            JButton b = new JButton(sl);
            // Τετράγωνο 20x20
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
                    if (x == b) x.setBackground(Color.GREEN);
                    else if (x.getBackground() == Color.GREEN) x.setBackground(Color.WHITE);
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
        
        // Get movie info for QR
        String movieInfo = getMovieInfoForShowtime(showtimeId);
        if (movieInfo == null) {
            JOptionPane.showMessageDialog(frame, "Σφάλμα φόρτωσης πληροφοριών ταινίας");
            return;
        }
        
        // Generate QR data
        String qrData = String.format(
            "TicketBook\nUser: %s\nMovie: %s\nSeat: %s\nTime: %s",
            currentUsername,
            movieInfo,
            seat,
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        );
        
        System.out.println("QR Data: " + qrData); // Debug
        
        String qrCodeBase64 = generateQRCodeImage(qrData);
        if (qrCodeBase64 == null) {
            JOptionPane.showMessageDialog(frame, "Σφάλμα δημιουργίας QR code");
            return;
        }
        
        System.out.println("QR Code length: " + qrCodeBase64.length()); // Debug
        
        try (Connection c = createConnection()) {
            // Insert booking with QR
            String insertSql = "INSERT INTO bookings(user_id,showtime_id,seat_number,status,qr_code) VALUES(?,?,?,?,?)";
            try (PreparedStatement ps = c.prepareStatement(insertSql)) {
                ps.setInt(1, currentUserId);
                ps.setInt(2, showtimeId);
                ps.setString(3, seat);
                ps.setString(4, "confirmed");
                ps.setString(5, qrCodeBase64);
                int affectedRows = ps.executeUpdate();
                
                System.out.println("Affected rows: " + affectedRows); // Debug
                
                if (affectedRows == 0) {
                    JOptionPane.showMessageDialog(frame, "Σφάλμα αποθήκευσης κράτησης");
                    return;
                }
            }

            // Mark seat as unavailable
            try (PreparedStatement ps2 = c.prepareStatement(
                    "UPDATE seats SET is_available=0 WHERE showtime_id=? AND seat_label=?")) {
                ps2.setInt(1, showtimeId);
                ps2.setString(2, seat);
                ps2.executeUpdate();
            }

            JOptionPane.showMessageDialog(frame, "Επιτυχής κράτηση!");
            cardLayout.show(mainPanel, "movies");
        } catch (SQLException ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Σφάλμα κράτησης: " + ex.getMessage());
        }
    });

    panel.add(grid,   BorderLayout.CENTER);
    JPanel bp = new JPanel();
    bp.add(btnBack);
    bp.add(btnBook);
    panel.add(bp, BorderLayout.SOUTH);
    return panel;
}
  // My bookings with QR code support - FIXED VERSION
private JPanel buildMyBookingsPage() {
    JPanel panel = new JPanel(new BorderLayout(10,10));
    panel.setBorder(BorderFactory.createEmptyBorder(20,20,20,20));
    DefaultListModel<String> model = new DefaultListModel<>();
    JList<String> list = new JList<>(model);
    
    // Store booking IDs separately to match with list items
    List<Integer> bookingIds = new ArrayList<>();

    String sql = "SELECT b.id, m.title, s.show_time, b.seat_number, b.qr_code " +
                 "FROM bookings b " +
                 "JOIN showtimes s ON b.showtime_id=s.id " +
                 "JOIN movies m ON s.movie_id=m.id " +
                 "WHERE b.user_id=? ORDER BY s.show_time";
    try (Connection c = createConnection();
         PreparedStatement st = c.prepareStatement(sql)) {
        st.setInt(1, currentUserId);
        ResultSet rs = st.executeQuery();
        while (rs.next()) {
            int bookingId = rs.getInt("id");
            bookingIds.add(bookingId); // Store the booking ID
            
            LocalDateTime dt = rs.getTimestamp("show_time").toLocalDateTime();
            String day = capitalizeGreekDay(dt.getDayOfWeek());
            String lbl = String.format(
              "%s %d/%d %02d:%02d - %s (Θέση %s)",
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

    list.addListSelectionListener(e -> {
        if (!e.getValueIsAdjusting() && list.getSelectedIndex() >= 0) {
            int selectedIndex = list.getSelectedIndex();
            if (selectedIndex >= 0 && selectedIndex < bookingIds.size()) {
                int bookingId = bookingIds.get(selectedIndex);
                
                // Now get the QR code for this booking ID
                String qrSql = "SELECT qr_code FROM bookings WHERE id=?";
                try (Connection c = createConnection();
                     PreparedStatement st = c.prepareStatement(qrSql)) {
                    st.setInt(1, bookingId);
                    ResultSet rs = st.executeQuery();
                    if (rs.next()) {
                        String qrCode = rs.getString("qr_code");
                        if (qrCode != null && !qrCode.isEmpty()) {
                            System.out.println("Found QR code for booking " + bookingId + ", length: " + qrCode.length());
                            showQRCodeDialog(qrCode);
                        } else {
                            System.out.println("No QR code found for booking " + bookingId);
                            JOptionPane.showMessageDialog(frame, "Δεν βρέθηκε QR code για αυτή την κράτηση");
                        }
                    }
                } catch (SQLException ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(frame, "Σφάλμα φόρτωσης QR code: " + ex.getMessage());
                }
            }
        }
    });

    JButton btnBack = new JButton("Πίσω");
    btnBack.addActionListener(e -> cardLayout.show(mainPanel, "postLoginHome"));

    panel.add(new JScrollPane(list), BorderLayout.CENTER);
    panel.add(btnBack, BorderLayout.SOUTH);
    return panel;
}

    // QR Code Helper Methods
    private String generateQRCodeImage(String text) {
        try {
            System.out.println("Generating QR for: " + text);
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, 200, 200);
            
            BufferedImage qrImage = MatrixToImageWriter.toBufferedImage(bitMatrix);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(qrImage, "PNG", baos);
            
            String result = Base64.getEncoder().encodeToString(baos.toByteArray());
            System.out.println("Generated QR size: " + result.length());
            return result;
        } catch (WriterException | IOException e) {
            System.err.println("QR Generation Error:");
            e.printStackTrace();
            return null;
        }
    }

    private ImageIcon getQRCodeIcon(String base64QR) {
        if (base64QR == null || base64QR.isEmpty()) {
            return null;
        }
        try {
            byte[] imageBytes = Base64.getDecoder().decode(base64QR);
            return new ImageIcon(imageBytes);
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid Base64 QR code data");
            return null;
        }
    }

    private void showQRCodeDialog(String base64QR) {
        if (base64QR == null || base64QR.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Δεν υπάρχει διαθέσιμο QR code");
            return;
        }
        
        ImageIcon qrIcon = getQRCodeIcon(base64QR);
        if (qrIcon == null) {
            JOptionPane.showMessageDialog(frame, "Σφάλμα φόρτωσης QR code");
            return;
        }
        
        JDialog dialog = new JDialog(frame, "QR Code", true);
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
    }

    private String getMovieInfoForShowtime(int showtimeId) {
        String sql = "SELECT m.title, s.show_time FROM showtimes s " +
                     "JOIN movies m ON s.movie_id = m.id " +
                     "WHERE s.id = ?";
        try (Connection c = createConnection();
             PreparedStatement st = c.prepareStatement(sql)) {
            st.setInt(1, showtimeId);
            ResultSet rs = st.executeQuery();
            if (rs.next()) {
                String title = rs.getString("title");
                Timestamp showTime = rs.getTimestamp("show_time");
                return String.format("%s (%s)", 
                    title, 
                    showTime.toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM HH:mm")));
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return null;
    }

    // Helpers
    private Connection createConnection() throws SQLException {
        Dotenv d = Dotenv.load();
        return DriverManager.getConnection(
          d.get("DB_URL"),
          d.get("DB_USER"),
          d.get("PASSWORD")
        );
    }

    private String hashPassword(String pw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bs = md.digest(pw.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : bs) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String capitalizeGreekDay(DayOfWeek d) {
        switch (d) {
            case MONDAY:    return "Δευτέρα";
            case TUESDAY:   return "Τρίτη";
            case WEDNESDAY: return "Τετάρτη";
            case THURSDAY:  return "Πέμπτη";
            case FRIDAY:    return "Παρασκευή";
            case SATURDAY:  return "Σάββατο";
            case SUNDAY:    return "Κυριακή";
            default:        return d.toString();
        }
    }

    // MovieItem & Renderer
    private static class MovieItem {
        private final int id;
        private final String title;
        private final ImageIcon icon;
        private final String duration, stars;
        public MovieItem(int id, String title, ImageIcon icon, String duration, String stars) {
            this.id = id;
            this.title = title;
            this.icon = icon;
            this.duration = duration;
            this.stars = stars;
        }
        public int getId() { return id; }
        @Override
        public String toString() {
            return title + " (" + duration + ", " + stars + "★)";
        }
    }

    private static class MovieCellRenderer extends JPanel implements ListCellRenderer<MovieItem> {
        private final JLabel imgLabel = new JLabel();
        private final JLabel textLabel = new JLabel();
        public MovieCellRenderer() {
            setLayout(new BorderLayout(5,5));
            textLabel.setFont(textLabel.getFont().deriveFont(Font.BOLD));
            add(imgLabel, BorderLayout.WEST);
            add(textLabel, BorderLayout.CENTER);
        }
        @Override
        public Component getListCellRendererComponent(JList<? extends MovieItem> list,
                                                      MovieItem item, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            imgLabel.setIcon(item.icon);
            textLabel.setText(item.title + " - " + item.duration + " | Stars: " + item.stars);
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            setOpaque(true);
            return this;
        }
    }
}