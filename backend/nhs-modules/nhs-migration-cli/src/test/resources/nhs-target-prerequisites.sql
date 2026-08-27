CREATE TABLE sys_user (
    user_id BIGINT PRIMARY KEY,
    dept_id BIGINT,
    user_name VARCHAR(30) NOT NULL,
    nick_name VARCHAR(30) NOT NULL,
    user_type VARCHAR(10),
    email VARCHAR(50),
    phone_number VARCHAR(11),
    gender CHAR(1),
    password VARCHAR(100),
    status CHAR(1),
    del_flag CHAR(1),
    create_by BIGINT,
    create_time TIMESTAMP,
    remark VARCHAR(500)
);

CREATE TABLE sys_role (
    role_id BIGINT PRIMARY KEY,
    role_name VARCHAR(30) NOT NULL,
    role_key VARCHAR(100) NOT NULL,
    status CHAR(1),
    del_flag CHAR(1)
);

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id)
);

INSERT INTO sys_role(role_id, role_name, role_key, status, del_flag)
VALUES (1, '普通角色', 'common', '0', '0'), (2, '平台管理员', 'platform_admin', '0', '0');
