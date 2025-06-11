// ContactlessTableOrderingSystem.java
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.*;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class ContactlessTableOrderingSystem extends Application {

    // Model classes

    public static class MenuItem {
        private final IntegerProperty id = new SimpleIntegerProperty();
        private final StringProperty name = new SimpleStringProperty();
        private final DoubleProperty price = new SimpleDoubleProperty();

        public MenuItem(int id, String name, double price) {
            setId(id);
            setName(name);
            setPrice(price);
        }

        public int getId() { return id.get(); }

        public String getName() { return name.get(); }

        public double getPrice() { return price.get(); }

        public void setId(int v) { id.set(v); }

        public void setName(String v) { name.set(v); }

        public void setPrice(double v) { price.set(v); }

        public IntegerProperty idProperty() { return id; }

        public StringProperty nameProperty() { return name; }

        public DoubleProperty priceProperty() { return price; }

        @Override
        public String toString() {
            return getId() + "," + getName() + "," + getPrice();
        }

        public static MenuItem fromString(String line) {
            try {
                String[] parts = line.split(",", 3);
                if(parts.length !=3) return null;
                int id = Integer.parseInt(parts[0]);
                String name = parts[1];
                double price = Double.parseDouble(parts[2]);
                return new MenuItem(id, name, price);
            } catch(Exception e) { return null; }
        }
    }

    public static class OrderItem {
        private final MenuItem item;
        private final int quantity;

        public OrderItem(MenuItem item, int quantity) {
            this.item = item;
            this.quantity = quantity;
        }

        public MenuItem getItem() { return item; }

        public int getQuantity() { return quantity; }

        @Override
        public String toString() { return item.getId() + ":" + quantity; }
    }

    public static class Order {
        private final IntegerProperty orderId = new SimpleIntegerProperty();
        private final IntegerProperty tableId = new SimpleIntegerProperty();
        private final ObservableList<OrderItem> items = FXCollections.observableArrayList();
        private final BooleanProperty completed = new SimpleBooleanProperty(false);

        public Order(int orderId, int tableId) {
            setOrderId(orderId);
            setTableId(tableId);
        }

        public int getOrderId() { return orderId.get(); }

        public int getTableId() { return tableId.get(); }

        public ObservableList<OrderItem> getItems() { return items; }

        public boolean isCompleted() { return completed.get(); }

        public void setOrderId(int v) { orderId.set(v); }

        public void setTableId(int v) { tableId.set(v); }

        public void setCompleted(boolean v) { completed.set(v); }

        public IntegerProperty orderIdProperty() { return orderId; }

        public IntegerProperty tableIdProperty() { return tableId; }

        public BooleanProperty completedProperty() { return completed; }

        public void addItem(OrderItem item) { items.add(item); }

        public void complete() { setCompleted(true); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(getOrderId()).append(",").append(getTableId()).append(",");
            items.forEach(i -> sb.append(i.toString()).append(";"));
            sb.append(",").append(isCompleted());
            return sb.toString();
        }

        public static Order fromString(String line, Map<Integer, MenuItem> menuMap) {
            try {
                String[] parts = line.split(",", 4);
                if(parts.length < 4) return null;
                int oid = Integer.parseInt(parts[0]);
                int tid = Integer.parseInt(parts[1]);
                String itemsStr= parts[2];
                boolean compl = Boolean.parseBoolean(parts[3]);
                Order o = new Order(oid, tid);
                String[] tokens = itemsStr.split(";");
                for(String t : tokens) {
                    if(t.trim().isEmpty()) continue;
                    String[] kv = t.split(":");
                    int mid = Integer.parseInt(kv[0]);
                    int qty = Integer.parseInt(kv[1]);
                    MenuItem mi = menuMap.get(mid);
                    if(mi!=null) {
                        o.addItem(new OrderItem(mi, qty));
                    }
                }
                o.setCompleted(compl);
                return o;
            } catch(Exception e){return null;}
        }
    }

    public static class Table {
        private final IntegerProperty id = new SimpleIntegerProperty();
        private final BooleanProperty occupied = new SimpleBooleanProperty(false);

        public Table(int id) { setId(id); }

        public int getId() { return id.get(); }

        public boolean isOccupied() { return occupied.get(); }

        public void setId(int v) { id.set(v); }

        public void setOccupied(boolean v) { occupied.set(v); }

        public IntegerProperty idProperty() { return id; }

        public BooleanProperty occupiedProperty() { return occupied; }

        @Override
        public String toString() { return getId() + "," + isOccupied(); }

        public static Table fromString(String line) {
            try {
                String[] parts = line.split(",",2);
                if(parts.length<2) return null;
                int id = Integer.parseInt(parts[0]);
                boolean occ = Boolean.parseBoolean(parts[1]);
                Table t = new Table(id);
                t.setOccupied(occ);
                return t;
            } catch(Exception e) {return null;}
        }
    }

    // Runnable Classes (5)

    // 1. OrderProcessor
    public static class OrderProcessor implements Runnable {
        private final BlockingQueue<Order> orderQueue;
        private final Map<Integer, Order> orderDatabase;
        private final File orderFile;
        private final BlockingQueue<String> notifications;
        private volatile boolean running = true;

        public OrderProcessor(BlockingQueue<Order> orderQueue, Map<Integer, Order> orderDatabase, File orderFile, BlockingQueue<String> notifications) {
            this.orderQueue = orderQueue;
            this.orderDatabase = orderDatabase;
            this.orderFile = orderFile;
            this.notifications = notifications;
        }

        public void stop() {
            running = false;
        }

        @Override
        public void run() {
            while (running) {
                try {
                    Order order = orderQueue.take();
                    notifications.put("Processing Order #" + order.getOrderId());
                    // Simulate processing time
                    Thread.sleep(2500);
                    // Do NOT mark order complete automatically
                    // Just update database to reflect processed state if needed (optional)
                    synchronized(orderDatabase) {
                        orderDatabase.put(order.getOrderId(), order);
                        saveOrders();
                    }
                    notifications.put("Order #" + order.getOrderId() + " processed. Awaiting manual completion.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        private void saveOrders() {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(orderFile))) {
                for(Order o : orderDatabase.values()) {
                    bw.write(o.toString());
                    bw.newLine();
                }
            } catch(IOException e) {
                System.out.println("Failed to save orders: " + e.getMessage());
            }
        }
    }

    // 2. StaffNotifier
    public static class StaffNotifier implements Runnable {
        private final BlockingQueue<String> notifications;
        private final Consumer<String> notifyConsumer;
        private volatile boolean running = true;

        public StaffNotifier(BlockingQueue<String> notifications, Consumer<String> notifyConsumer) {
            this.notifications = notifications;
            this.notifyConsumer = notifyConsumer;
        }

        public void stop() { running = false; }

        @Override
        public void run() {
            while(running) {
                try {
                    String msg = notifications.take();
                    Platform.runLater(() -> notifyConsumer.accept(msg));
                    Thread.sleep(500);
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    // 3. AdminManager (saving menu & tables periodically)
    public static class AdminManager implements Runnable {
        private final Map<Integer, MenuItem> menuDatabase;
        private final Map<Integer, Table> tableDatabase;
        private final File menuFile;
        private final File tableFile;
        private volatile boolean running = true;

        public AdminManager(Map<Integer, MenuItem> menuDatabase, Map<Integer, Table> tableDatabase, File menuFile, File tableFile) {
            this.menuDatabase = menuDatabase;
            this.tableDatabase = tableDatabase;
            this.menuFile = menuFile;
            this.tableFile = tableFile;
        }

        public void stop() { running = false; }

        @Override
        public void run() {
            while(running) {
                try {
                    Thread.sleep(30000);
                    saveMenu();
                    saveTables();
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        private void saveMenu() {
            try(BufferedWriter bw = new BufferedWriter(new FileWriter(menuFile))) {
                for(MenuItem mi : menuDatabase.values()) bw.write(mi.toString() + "\n");
            } catch(IOException e) { System.out.println("Failed to save menu: " + e.getMessage()); }
        }

        private void saveTables() {
            try(BufferedWriter bw = new BufferedWriter(new FileWriter(tableFile))) {
                for(Table t : tableDatabase.values()) bw.write(t.toString() + "\n");
            } catch(IOException e) { System.out.println("Failed to save tables: " + e.getMessage()); }
        }
    }

    // 4. FileManager (saving orders periodically)
    public static class FileManager implements Runnable {
        private final Map<Integer, Order> orderDatabase;
        private final File orderFile;
        private volatile boolean running = true;

        public FileManager(Map<Integer, Order> orderDatabase, File orderFile) {
            this.orderDatabase = orderDatabase;
            this.orderFile = orderFile;
        }

        public void stop() { running = false; }

        @Override
        public void run() {
            while(running) {
                try {
                    Thread.sleep(15000);
                    saveOrders();
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        private void saveOrders() {
            try(BufferedWriter bw = new BufferedWriter(new FileWriter(orderFile))) {
                for(Order o : orderDatabase.values()) bw.write(o.toString() + "\n");
            } catch(IOException e) { System.out.println("Failed to save orders: " + e.getMessage()); }
        }
    }

    // 5. BackupThread (backup files periodically)
    public static class BackupThread implements Runnable {
        private final File menuFile;
        private final File tableFile;
        private final File orderFile;
        private volatile boolean running = true;

        public BackupThread(File menuFile, File tableFile, File orderFile) {
            this.menuFile = menuFile;
            this.tableFile = tableFile;
            this.orderFile = orderFile;
        }

        public void stop() { running = false; }

        @Override
        public void run() {
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
            while(running) {
                try {
                    Thread.sleep(60000);
                    String timestamp = LocalDateTime.now().format(dtf);
                    backupFile(menuFile, "menu_backup_" + timestamp + ".txt");
                    backupFile(tableFile, "tables_backup_" + timestamp + ".txt");
                    backupFile(orderFile, "orders_backup_" + timestamp + ".txt");
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch(Exception e) {
                    e.printStackTrace();
                }
            }
        }

        private void backupFile(File source, String backupFileName) {
            if(source.exists()) {
                try {
                    File backup = new File(backupFileName);
                    Files.copy(source.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch(IOException e) {
                    System.out.println("Backup failed for " + source.getName() + ": " + e.getMessage());
                }
            }
        }
    }

    // LoggerThread (async logging)
    public static class LoggerThread implements Runnable {
        private final BlockingQueue<String> logQueue = new LinkedBlockingQueue<>();
        private final File logFile;
        private volatile boolean running = true;

        public LoggerThread(File logFile) { this.logFile = logFile; }

        public void log(String message) {
            if(running) {
                logQueue.offer(LocalDateTime.now() + " - " + message);
            }
        }

        public void stop() { running = false; }

        @Override
        public void run() {
            while(running || !logQueue.isEmpty()) {
                try {
                    String msg = logQueue.poll(1, TimeUnit.SECONDS);
                    if(msg != null) {
                        try(BufferedWriter bw = new BufferedWriter(new FileWriter(logFile,true))) {
                            bw.write(msg);
                            bw.newLine();
                        }
                    }
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch(IOException e) {
                    System.out.println("LoggerThread write error: " + e.getMessage());
                }
            }
        }
    }

    // Fields and Threads
    private final Map<Integer, MenuItem> menuDatabase = new ConcurrentHashMap<>();
    private final Map<Integer, Table> tableDatabase = new ConcurrentHashMap<>();
    private final Map<Integer, Order> orderDatabase = new ConcurrentHashMap<>();
    private final BlockingQueue<Order> orderQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> notifications = new LinkedBlockingQueue<>();

    private final File menuFile = new File("menu.txt");
    private final File tableFile = new File("tables.txt");
    private final File orderFile = new File("orders.txt");
    private final File logFile = new File("system.log");

    private int nextOrderId = 1000;

    private Thread orderProcessorThread;
    private Thread staffNotifierThread;
    private Thread adminManagerThread;
    private Thread fileManagerThread;
    private Thread backupThread;
    private Thread loggerThread;
    private LoggerThread logger;

    private Stage customerStage;
    private Stage staffStage;
    private Stage adminStage;

    private TableView<Order> tvOrders;
    private ListView<String> staffNotifications;
    private TableView<Table> tvTablesForCleaning;

    @Override
    public void start(Stage primaryStage) {
        loadDatabases();

        // Launch interfaces
        Platform.runLater(this::showCustomerInterface);
        Platform.runLater(this::showStaffInterface);
        Platform.runLater(this::showAdminInterface);

        // Start threads
        logger = new LoggerThread(logFile);
        loggerThread = new Thread(logger, "LoggerThread");
        loggerThread.setDaemon(true);
        loggerThread.start();

        orderProcessorThread = new Thread(new OrderProcessor(orderQueue, orderDatabase, orderFile, notifications), "OrderProcessor");
        staffNotifierThread = new Thread(new StaffNotifier(notifications, this::showStaffNotification), "StaffNotifier");
        adminManagerThread = new Thread(new AdminManager(menuDatabase, tableDatabase, menuFile, tableFile), "AdminManager");
        fileManagerThread = new Thread(new FileManager(orderDatabase, orderFile), "FileManager");
        backupThread = new Thread(new BackupThread(menuFile, tableFile, orderFile), "BackupThread");

        orderProcessorThread.setDaemon(true);
        staffNotifierThread.setDaemon(true);
        adminManagerThread.setDaemon(true);
        fileManagerThread.setDaemon(true);
        backupThread.setDaemon(true);

        orderProcessorThread.start();
        staffNotifierThread.start();
        adminManagerThread.start();
        fileManagerThread.start();
        backupThread.start();

        logger.log("System started.");
    }

    private void showCustomerInterface() {
        customerStage = new Stage();
        customerStage.setTitle("Customer Interface");

        Label lblTable = new Label("Select Table:");
        ComboBox<Table> cbTable = new ComboBox<>(FXCollections.observableArrayList(tableDatabase.values()));
        cbTable.setCellFactory(lv -> new ListCell<>() {
                    @Override
                    protected void updateItem(Table item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : "Table " + item.getId() + (item.isOccupied() ? " (Occupied)" : " (Free)"));
                    }
                });
        cbTable.setButtonCell(cbTable.getCellFactory().call(null));

        Label lblMenu= new Label("Select Menu Items:");
        ListView<MenuItem> lvMenu = new ListView<>(FXCollections.observableArrayList(menuDatabase.values()));
        lvMenu.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        Spinner<Integer> spQty = new Spinner<>(1,10,1);

        Button btnAdd = new Button("Add to Order");
        ListView<String> lvCurrentOrder = new ListView<>();
        ObservableList<OrderItem> currentOrderItems = FXCollections.observableArrayList();

        btnAdd.setOnAction(e-> {
                    ObservableList<MenuItem> selected = lvMenu.getSelectionModel().getSelectedItems();
                    if(selected.isEmpty()) return;
                    int qty = spQty.getValue();
                    for(MenuItem mi: selected) {
                        currentOrderItems.add(new OrderItem(mi, qty));
                        lvCurrentOrder.getItems().add(mi.getName() + " x" + qty);
                    }
            });

        Button btnClear = new Button("Clear Order");
        btnClear.setOnAction(e -> {
                    currentOrderItems.clear();
                    lvCurrentOrder.getItems().clear();
            });

        Button btnSubmit = new Button("Submit Order");
        btnSubmit.setOnAction(e -> {
                    Table selected = cbTable.getSelectionModel().getSelectedItem();
                    if(selected == null) { showAlert("Select a table."); return; }
                    if(selected.isOccupied()) { showAlert("Table occupied."); return; }
                    if(currentOrderItems.isEmpty()) { showAlert("Add items to order."); return; }
                    synchronized(tableDatabase) { selected.setOccupied(true); }
                    Order order = new Order(nextOrderId++, selected.getId());
                    currentOrderItems.forEach(order::addItem);
                    synchronized(orderDatabase) { orderDatabase.put(order.getOrderId(), order); }
                    try {
                        orderQueue.put(order);
                        notifications.put("Order #" + order.getOrderId() + " placed on Table " + selected.getId());
                        logger.log("Order #" + order.getOrderId() + " placed on Table " + selected.getId());
                    } catch(InterruptedException ex) { ex.printStackTrace(); }
                    currentOrderItems.clear();
                    lvCurrentOrder.getItems().clear();
            });

        VBox vb = new VBox(10, lblTable, cbTable, lblMenu, lvMenu,
                new HBox(5,new Label("Quantity:"),spQty),
                btnAdd, new Label("Current Order:"), lvCurrentOrder,
                new HBox(10, btnSubmit, btnClear));
        vb.setPadding(new Insets(10));
        customerStage.setScene(new Scene(vb,400,500));
        customerStage.show();
    }

    private void showStaffInterface() {
        staffStage = new Stage();
        staffStage.setTitle("Staff Interface");

        tvOrders = new TableView<>(FXCollections.observableArrayList(orderDatabase.values()));
        TableColumn<Order,Number> colOrder = new TableColumn<>("Order ID");
        colOrder.setCellValueFactory(c->c.getValue().orderIdProperty());
        TableColumn<Order,Number> colTable = new TableColumn<>("Table ID");
        colTable.setCellValueFactory(c->c.getValue().tableIdProperty());
        TableColumn<Order,String> colItems = new TableColumn<>("Items");
        colItems.setCellValueFactory(c -> {
                    StringBuilder sb = new StringBuilder();
                    for(OrderItem oi : c.getValue().getItems()) {
                        sb.append(oi.getItem().getName()).append(" x").append(oi.getQuantity()).append("; ");
                    }
                    return new ReadOnlyStringWrapper(sb.toString());
            });
        TableColumn<Order,String> colComp = new TableColumn<>("Completed");
        colComp.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().isCompleted() ? "YES":"NO"));
        tvOrders.getColumns().addAll(colOrder,colTable,colItems,colComp);
        tvOrders.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        Button btnComplete = new Button("Mark Order Completed");
        btnComplete.setOnAction(e-> {
                    Order sel = tvOrders.getSelectionModel().getSelectedItem();
                    if(sel == null) {showAlert("Select order");return;}
                    if(sel.isCompleted()) {showAlert("Order already completed");return;}
                    sel.complete();
                    synchronized(orderDatabase) { orderDatabase.put(sel.getOrderId(), sel); }
                    try {
                        notifications.put("Order #" + sel.getOrderId() + " marked completed");
                        logger.log("Order #" + sel.getOrderId() + " marked completed");
                    } catch(InterruptedException ex){ex.printStackTrace();}
                    tvOrders.refresh();
            });

        tvTablesForCleaning = new TableView<>(FXCollections.observableArrayList(tableDatabase.values()));
        TableColumn<Table,Number> colTid = new TableColumn<>("Table ID");
        colTid.setCellValueFactory(c->c.getValue().idProperty());
        TableColumn<Table,Boolean> colOcc = new TableColumn<>("Occupied");
        colOcc.setCellValueFactory(c->c.getValue().occupiedProperty());
        colOcc.setCellFactory(CheckBoxTableCell.forTableColumn(colOcc));
        colOcc.setEditable(false);
        tvTablesForCleaning.getColumns().addAll(colTid,colOcc);
        tvTablesForCleaning.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tvTablesForCleaning.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        Button btnMarkCleaned = new Button("Mark Selected Table(s) Cleaned");
        btnMarkCleaned.setOnAction(e-> {
                    ObservableList<Table> selected = tvTablesForCleaning.getSelectionModel().getSelectedItems();
                    if(selected.isEmpty()) { showAlert("Select tables"); return; }
                    synchronized(tableDatabase) {
                        for(Table t : selected) {
                            if(t.isOccupied()) {
                                t.setOccupied(false);
                                logger.log("Staff marked table "+t.getId()+" cleaned");
                                try { notifications.put("Table "+t.getId()+" cleaned"); } catch(Exception ignored) {}
                            }
                        }
                    }
                    tvTablesForCleaning.refresh();
            });

        staffNotifications = new ListView<>();
        staffNotifications.setPrefHeight(150);

        VBox ordersBox = new VBox(10, new Label("Orders"), tvOrders, btnComplete);
        ordersBox.setPrefWidth(600);
        ordersBox.setPadding(new Insets(10));
        VBox tablesBox = new VBox(10, new Label("Tables (Manual Cleaning)"), tvTablesForCleaning, btnMarkCleaned, new Label("Notifications"), staffNotifications);
        tablesBox.setPrefWidth(400);
        tablesBox.setPadding(new Insets(10));

        HBox layout = new HBox(10, ordersBox, tablesBox);
        staffStage.setScene(new Scene(layout, 1020, 500));
        staffStage.show();

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(() -> Platform.runLater(() -> {
                            tvOrders.setItems(FXCollections.observableArrayList(orderDatabase.values()));
                            tvTablesForCleaning.setItems(FXCollections.observableArrayList(tableDatabase.values()));
                    }),0,3, TimeUnit.SECONDS);
    }

    private void showStaffNotification(String msg) {
        staffNotifications.getItems().add(msg);
    }

    private void showAdminInterface() {
        adminStage = new Stage();
        adminStage.setTitle("Admin Interface");

        TableView<MenuItem> tvMenu = new TableView<>(FXCollections.observableArrayList(menuDatabase.values()));
        TableColumn<MenuItem,Integer> colId = new TableColumn<>("ID");
        colId.setCellValueFactory(c->c.getValue().idProperty().asObject());
        TableColumn<MenuItem,String> colName = new TableColumn<>("Name");
        colName.setCellValueFactory(c->c.getValue().nameProperty());
        TableColumn<MenuItem,Double> colPrice = new TableColumn<>("Price");
        colPrice.setCellValueFactory(c->c.getValue().priceProperty().asObject());
        tvMenu.getColumns().addAll(colId,colName,colPrice);
        tvMenu.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TextField tfName = new TextField();
        tfName.setPromptText("Name");
        TextField tfPrice = new TextField();
        tfPrice.setPromptText("Price");
        Button btnAddMenu = new Button("Add Menu Item");

        btnAddMenu.setOnAction(e-> {
                    String name=tfName.getText().trim();
                    String priceStr=tfPrice.getText().trim();
                    if(name.isEmpty()||priceStr.isEmpty()) { showAlert("Fill name and price"); return; }
                    double price;
                    try { price = Double.parseDouble(priceStr); } catch (Exception ex) { showAlert("Invalid price"); return; }
                    int newId = menuDatabase.keySet().stream().max(Integer::compareTo).orElse(0)+1;
                    MenuItem mi=new MenuItem(newId,name,price);
                    menuDatabase.put(newId,mi);
                    tvMenu.setItems(FXCollections.observableArrayList(menuDatabase.values()));
                    tfName.clear();tfPrice.clear();
                    logger.log("Admin added menu item "+name);
            });

        TableView<Table> tvTables = new TableView<>(FXCollections.observableArrayList(tableDatabase.values()));
        TableColumn<Table,Integer> colTableId = new TableColumn<>("Table ID");
        colTableId.setCellValueFactory(c->c.getValue().idProperty().asObject());
        TableColumn<Table,Boolean> colOccupied = new TableColumn<>("Occupied");
        colOccupied.setCellValueFactory(c->c.getValue().occupiedProperty());
        colOccupied.setCellFactory(CheckBoxTableCell.forTableColumn(colOccupied));
        colOccupied.setEditable(false);
        tvTables.getColumns().addAll(colTableId,colOccupied);
        tvTables.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        Button btnAddTable = new Button("Add Table");
        btnAddTable.setOnAction(e-> {
                    int newId = tableDatabase.keySet().stream().max(Integer::compareTo).orElse(0)+1;
                    Table t = new Table(newId);
                    tableDatabase.put(newId,t);
                    tvTables.setItems(FXCollections.observableArrayList(tableDatabase.values()));
                    logger.log("Admin added table "+newId);
            });

        VBox vMenu = new VBox(5, new Label("Menu Items"), tvMenu, new HBox(5, tfName, tfPrice, btnAddMenu));
        VBox vTables = new VBox(5, new Label("Tables"), tvTables, btnAddTable);
        vMenu.setPadding(new Insets(10));
        vTables.setPadding(new Insets(10));

        HBox root = new HBox(10, vMenu, vTables);
        adminStage.setScene(new Scene(root, 700, 500));
        adminStage.show();
    }

    private void showAlert(String msg) {
        Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Information");
                    alert.setContentText(msg);
                    alert.showAndWait();
            });
    }

    // Load/save DB files

    private void loadDatabases() {
        loadMenu();
        loadTables();
        loadOrders();
    }

    private void loadMenu() {
        if(!menuFile.exists()) {
            menuDatabase.put(1,new MenuItem(1,"Burger",5.99));
            menuDatabase.put(2,new MenuItem(2,"Fries",2.49));
            menuDatabase.put(3,new MenuItem(3,"Soda",1.99));
            saveMenu();
            return;
        }
        try(BufferedReader br=new BufferedReader(new FileReader(menuFile))) {
            String l;
            while((l=br.readLine())!=null) {
                MenuItem mi=MenuItem.fromString(l);
                if(mi!=null) menuDatabase.put(mi.getId(),mi);
            }
        } catch(IOException e) {
            System.err.println("Load Menu Error: "+e.getMessage());
        }
    }

    private void saveMenu() {
        try(BufferedWriter bw=new BufferedWriter(new FileWriter(menuFile))) {
            for(MenuItem mi:menuDatabase.values()) {
                bw.write(mi.toString());
                bw.newLine();
            }
        } catch(IOException e) {
            System.err.println("Save Menu Error: "+e.getMessage());
        }
    }

    private void loadTables() {
        if(!tableFile.exists()) {
            for(int i=1;i<=5;i++) tableDatabase.put(i,new Table(i));
            saveTables();
            return;
        }
        try(BufferedReader br=new BufferedReader(new FileReader(tableFile))) {
            String l;
            while((l=br.readLine())!=null) {
                Table t=Table.fromString(l);
                if(t!=null) tableDatabase.put(t.getId(),t);
            }
        } catch(IOException e) {
            System.err.println("Load Tables Error: "+e.getMessage());
        }
    }

    private void saveTables() {
        try(BufferedWriter bw=new BufferedWriter(new FileWriter(tableFile))) {
            for(Table t:tableDatabase.values()) {
                bw.write(t.toString());
                bw.newLine();
            }
        } catch(IOException e) {
            System.err.println("Save Tables Error: "+e.getMessage());
        }
    }

    private void loadOrders() {
        if(!orderFile.exists()) return;
        try(BufferedReader br=new BufferedReader(new FileReader(orderFile))) {
            String l;
            while((l=br.readLine())!=null) {
                Order o=Order.fromString(l,menuDatabase);
                if(o!=null) {
                    orderDatabase.put(o.getOrderId(),o);
                    nextOrderId=Math.max(nextOrderId,o.getOrderId()+1);
                }
            }
        } catch(IOException e) {
            System.err.println("Load Orders Error: "+e.getMessage());
        }
    }

    @Override
    public void stop() throws Exception {
        if(orderProcessorThread!=null) orderProcessorThread.interrupt();
        if(staffNotifierThread!=null) staffNotifierThread.interrupt();
        if(adminManagerThread!=null) adminManagerThread.interrupt();
        if(fileManagerThread!=null) fileManagerThread.interrupt();
        if(backupThread!=null) backupThread.interrupt();
        if(logger!=null) logger.stop();
        if(loggerThread!=null) loggerThread.interrupt();
        saveMenu();
        saveTables();
        super.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
