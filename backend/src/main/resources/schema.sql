CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  display_name VARCHAR(64) NOT NULL,
  role_code VARCHAR(32) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_role (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(32) NOT NULL UNIQUE,
  name VARCHAR(64) NOT NULL,
  description VARCHAR(255),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  built_in BOOLEAN NOT NULL DEFAULT FALSE,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_permission (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(64) NOT NULL,
  module VARCHAR(32) NOT NULL,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_user_role (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (user_id, role_id),
  CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role(id)
);

CREATE TABLE IF NOT EXISTS sys_role_permission (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  role_id BIGINT NOT NULL,
  permission_id BIGINT NOT NULL,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (role_id, permission_id),
  CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES sys_role(id),
  CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES sys_permission(id)
);

CREATE TABLE IF NOT EXISTS building (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(32) NOT NULL UNIQUE,
  name VARCHAR(64) NOT NULL UNIQUE,
  gender_type VARCHAR(16) NOT NULL,
  floors INT NOT NULL,
  manager VARCHAR(64),
  status VARCHAR(16) NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_building_status (status)
);

CREATE TABLE IF NOT EXISTS dormitory (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,
  type VARCHAR(32) NOT NULL,
  building VARCHAR(32) NOT NULL,
  beds INT NOT NULL,
  occupied INT NOT NULL,
  vacant INT NOT NULL,
  status VARCHAR(16) NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (building, name),
  INDEX idx_dormitory_building (building),
  INDEX idx_dormitory_status (status),
  INDEX idx_dormitory_type (type)
);

CREATE TABLE IF NOT EXISTS dormitory_building (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  dormitory_id BIGINT NOT NULL UNIQUE,
  building_id BIGINT NOT NULL,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_dormitory_building_relation (building_id),
  CONSTRAINT fk_dormitory_building_dormitory FOREIGN KEY (dormitory_id) REFERENCES dormitory(id),
  CONSTRAINT fk_dormitory_building_building FOREIGN KEY (building_id) REFERENCES building(id)
);

CREATE TABLE IF NOT EXISTS student (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  student_no VARCHAR(32) NOT NULL UNIQUE,
  name VARCHAR(32) NOT NULL,
  gender VARCHAR(8) NOT NULL,
  college VARCHAR(64) NOT NULL,
  grade VARCHAR(16) NOT NULL,
  phone VARCHAR(32) NOT NULL,
  check_in_status VARCHAR(16) NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_student_check_in_status (check_in_status),
  INDEX idx_student_college_grade (college, grade)
);

CREATE TABLE IF NOT EXISTS bed (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  dormitory_id BIGINT NOT NULL,
  bed_no VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL,
  student_id BIGINT,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (dormitory_id, bed_no),
  INDEX idx_bed_dormitory_status (dormitory_id, status),
  INDEX idx_bed_status (status),
  INDEX idx_bed_student (student_id),
  CONSTRAINT fk_bed_dormitory FOREIGN KEY (dormitory_id) REFERENCES dormitory(id),
  CONSTRAINT fk_bed_student FOREIGN KEY (student_id) REFERENCES student(id)
);

CREATE TABLE IF NOT EXISTS check_in_application (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  student_no VARCHAR(32) NOT NULL,
  name VARCHAR(32) NOT NULL,
  dormitory VARCHAR(64) NOT NULL,
  date VARCHAR(20) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_by_user_id BIGINT,
  applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_application_status (status),
  INDEX idx_application_date (date),
  INDEX idx_application_creator (created_by_user_id),
  CONSTRAINT fk_application_creator FOREIGN KEY (created_by_user_id) REFERENCES sys_user(id)
);

CREATE TABLE IF NOT EXISTS check_in_application_detail (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  application_id BIGINT NOT NULL UNIQUE,
  student_id BIGINT NOT NULL,
  dormitory_id BIGINT NOT NULL,
  bed_id BIGINT,
  apply_remark VARCHAR(255),
  review_remark VARCHAR(255),
  reviewer_user_id BIGINT,
  reviewed_at TIMESTAMP,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_application_detail_student (student_id),
  INDEX idx_application_detail_dormitory (dormitory_id),
  INDEX idx_application_detail_bed (bed_id),
  CONSTRAINT fk_application_detail_application FOREIGN KEY (application_id) REFERENCES check_in_application(id),
  CONSTRAINT fk_application_detail_student FOREIGN KEY (student_id) REFERENCES student(id),
  CONSTRAINT fk_application_detail_dormitory FOREIGN KEY (dormitory_id) REFERENCES dormitory(id),
  CONSTRAINT fk_application_detail_bed FOREIGN KEY (bed_id) REFERENCES bed(id),
  CONSTRAINT fk_application_reviewer FOREIGN KEY (reviewer_user_id) REFERENCES sys_user(id)
);

CREATE TABLE IF NOT EXISTS check_in_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  student_id BIGINT NOT NULL,
  bed_id BIGINT NOT NULL,
  application_id BIGINT,
  active_student_id BIGINT UNIQUE,
  active_bed_id BIGINT UNIQUE,
  check_in_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  check_out_date TIMESTAMP,
  status VARCHAR(16) NOT NULL,
  remark VARCHAR(255),
  check_in_operator_user_id BIGINT,
  check_out_operator_user_id BIGINT,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (application_id),
  INDEX idx_check_in_record_student_status (student_id, status),
  INDEX idx_check_in_record_bed_status (bed_id, status),
  INDEX idx_check_in_record_status (status),
  CONSTRAINT fk_check_in_record_student FOREIGN KEY (student_id) REFERENCES student(id),
  CONSTRAINT fk_check_in_record_bed FOREIGN KEY (bed_id) REFERENCES bed(id),
  CONSTRAINT fk_check_in_record_application FOREIGN KEY (application_id) REFERENCES check_in_application(id),
  CONSTRAINT fk_check_in_record_active_student FOREIGN KEY (active_student_id) REFERENCES student(id),
  CONSTRAINT fk_check_in_record_active_bed FOREIGN KEY (active_bed_id) REFERENCES bed(id),
  CONSTRAINT fk_check_in_operator FOREIGN KEY (check_in_operator_user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_check_out_operator FOREIGN KEY (check_out_operator_user_id) REFERENCES sys_user(id)
);

CREATE TABLE IF NOT EXISTS repair_order (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL UNIQUE,
  reporter VARCHAR(32) NOT NULL,
  location VARCHAR(64) NOT NULL,
  type VARCHAR(32) NOT NULL,
  date VARCHAR(20) NOT NULL,
  status VARCHAR(16) NOT NULL,
  description VARCHAR(255),
  assignee_user_id BIGINT,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_repair_status (status),
  INDEX idx_repair_type (type),
  INDEX idx_repair_assignee_status (assignee_user_id, status),
  CONSTRAINT fk_repair_assignee FOREIGN KEY (assignee_user_id) REFERENCES sys_user(id)
);

CREATE TABLE IF NOT EXISTS repair_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  repair_order_id BIGINT NOT NULL,
  handler VARCHAR(32) NOT NULL,
  content VARCHAR(255) NOT NULL,
  cost DECIMAL(10,2) NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL,
  handled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  operator_user_id BIGINT,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_repair_record_order (repair_order_id),
  CONSTRAINT fk_repair_record_order FOREIGN KEY (repair_order_id) REFERENCES repair_order(id),
  CONSTRAINT fk_repair_record_operator FOREIGN KEY (operator_user_id) REFERENCES sys_user(id)
);

CREATE TABLE IF NOT EXISTS payment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  student_no VARCHAR(32) NOT NULL,
  name VARCHAR(32) NOT NULL,
  type VARCHAR(32) NOT NULL,
  amount_due DECIMAL(10,2) NOT NULL,
  amount_paid DECIMAL(10,2) NOT NULL,
  status VARCHAR(16) NOT NULL,
  deadline VARCHAR(20) NOT NULL,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_payment_status (status),
  INDEX idx_payment_student_type (student_no, type)
);

CREATE TABLE IF NOT EXISTS payment_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  payment_id BIGINT NOT NULL,
  amount DECIMAL(10,2) NOT NULL,
  method VARCHAR(32) NOT NULL,
  paid_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  operator_user_id BIGINT,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_payment_record_bill (payment_id),
  CONSTRAINT fk_payment_record_bill FOREIGN KEY (payment_id) REFERENCES payment(id),
  CONSTRAINT fk_payment_record_operator FOREIGN KEY (operator_user_id) REFERENCES sys_user(id)
);

CREATE TABLE IF NOT EXISTS hygiene_check (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  dormitory VARCHAR(64) NOT NULL,
  building VARCHAR(32) NOT NULL,
  date VARCHAR(20) NOT NULL,
  inspector VARCHAR(32) NOT NULL,
  score INT NOT NULL,
  result VARCHAR(16) NOT NULL,
  remark VARCHAR(255),
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_hygiene_result (result),
  INDEX idx_hygiene_location (building, dormitory)
);

CREATE TABLE IF NOT EXISTS notice (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  title VARCHAR(128) NOT NULL,
  type VARCHAR(32) NOT NULL,
  date VARCHAR(20) NOT NULL,
  publisher VARCHAR(32) NOT NULL,
  status VARCHAR(16) NOT NULL,
  content MEDIUMTEXT NULL,
  published_at TIMESTAMP(6) NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_notice_status_published (status, published_at),
  INDEX idx_notice_type (type)
);
