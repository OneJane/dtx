# Seata
导入bank1.sql,bank2.sql,创建account_info,undo_log

[seata服务器](https://github.com/seata/seata/releases/download/v0.7.1/seata-server-0.7.1.zip) 并运行seata-server.bat -p 8888 -m file ，其中file为启动模式，启动事务协调器TC

discover-server 基于eureka实现服务注册中心