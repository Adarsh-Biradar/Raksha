ALTER TABLE transactions ADD COLUMN ip_address VARCHAR(45), ADD COLUMN phone_number VARCHAR(16);
CREATE TABLE detection_rules (
 code TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT true, points INT NOT NULL CHECK(points BETWEEN 1 AND 100),
 match_values TEXT NOT NULL DEFAULT '', version INT NOT NULL DEFAULT 1
);
INSERT INTO detection_rules(code,name,description,points) VALUES
('LARGE_AMOUNT','Large payment','Amount meets the large-payment threshold in Risk policy.',25),
('ANOMALY','Unusual amount','At least five prior events, amount at least 3x average and deviation at least 3.',30),
('NEW_DEVICE','Unfamiliar device','Device not seen in at least five prior account events.',15),
('NEW_COUNTRY','Unfamiliar country','Country not seen in at least five prior account events.',15),
('VELOCITY','Rapid payments','Five-minute transaction count meets the Risk policy limit.',25),
('FAILED_ATTEMPTS','Failed attempts','Three or more preceding failed attempts.',25),
('BLOCKED_IP','Blocked IP address','Exact IPv4 addresses or IPv4 CIDR ranges from the negative list.',100),
('BLOCKED_PHONE','Blocked phone number','Exact phone-number match, normalized with international country code.',100);
