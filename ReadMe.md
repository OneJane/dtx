# Seata实现2PC
导入bank1.sql,bank2.sql,创建account_info,undo_log

[seata服务器](https://github.com/seata/seata/releases/download/v0.7.1/seata-server-0.7.1.zip) 并运行seata-server.bat -p 8888 -m file ，其中file为启动模式，启动事务协调器TC

discover-server 基于eureka实现服务注册中心

dtx-seata-bank1 操作张三账户，连接数据库bank1 
dtx-seata-bank2 操作李四账户，连接数据库bank2 

在dtx父工程中指定了SpringBoot和SpringCloud版本 

```
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-dependencies</artifactId>
    <version>2.1.3.RELEASE</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>

<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-dependencies</artifactId>
    <version>Greenwich.RELEASE</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

在dtx-seata父工程中指定了spring-cloud-alibaba-dependencies的版本

```
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-alibaba-dependencies</artifactId>
    <version>2.1.0.RELEASE</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

在src/main/resource中，新增registry.conf、fifile.conf文件，内容可拷贝seata-server-0.7.1中的配置文件

在registry.conf中registry.type使用file,在fifile.conf中更改service.vgroup_mapping.[springcloud服务名]-fescar-service-group = "default"，并修改 service.default.grouplist =[seata服务端地址]

在 org.springframework.cloud:spring-cloud-starter-alibaba-seata 的 org.springframework.cloud.alibaba.seata.GlobalTransactionAutoConfiguration 类中，默认会使用${spring.application.name}-fescar-service-group 作为事务分组服务名注册到 Seata Server上，如果和file.conf 中的配置不一致，会提示 no available server to connect 错误也可以通过配置 **spring.cloud.alibaba.seata.tx-service-group** 修改后缀，但是必须和 file.conf 中的配置保持一致。

创建代理数据源,Seata的RM通过DataSourceProxy才能在业务代码的事务提交时，通过这个切入点，与TC进行通信交互、记录undo_log等。

```
@Configuration
public class DatabaseConfiguration {
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.ds0")
    public DruidDataSource ds0() {
        DruidDataSource druidDataSource = new DruidDataSource();
        return druidDataSource;
    }
    @Primary
    @Bean
    public DataSource dataSource(DruidDataSource ds0)  {
        DataSourceProxy pds0 = new DataSourceProxy(ds0);
        return pds0;
    }
}
```

Seata执行流程

![image-20201203140943702](upload\image-20201203140943702.png)

Seata回滚流程

![image-20201203141155105](upload\image-20201203141155105.png)

1、每个RM使用DataSourceProxy连接数据库，其目的是使用ConnectionProxy，使用数据源和数据连接代理的目的就是在第一阶段将undo_log和业务数据放在一个本地事务提交，这样就保存了只要有业务操作就一定有 undo_log。 

2、在第一阶段undo_log中存放了数据修改前和修改后的值，为事务回滚作好准备，所以第一阶段完成就已经将分 支事务提交，也就释放了锁资源。 

3、TM开启全局事务开始，将XID全局事务id放在事务上下文中，通过feign调用也将XID传入下游分支事务，每个 分支事务将自己的Branch ID分支事务ID与XID关联。 

4、第二阶段全局事务提交，TC会通知各各分支参与者提交分支事务，在第一阶段就已经提交了分支事务，这里各 各参与者只需要删除undo_log即可，并且可以异步执行，第二阶段很快可以完成。 

5、第二阶段全局事务回滚，TC会通知各各分支参与者回滚分支事务，通过 XID 和 Branch ID 找到相应的回滚日 志，通过回滚日志生成反向的 SQL 并执行，以完成分支事务回滚到之前的状态，如果回滚失败则会重试回滚作。

## dtx-seata-bank1

1、张三账户减少金额，开启全局事务。

2、远程调用bank2向李四转账。 

```
@Mapper
@Component
public interface AccountInfoDao {
    //更新账户金额
    @Update("update account_info set account_balance = account_balance + #{amount} where account_no = #{accountNo}")
    int updateAccountBalance(@Param("accountNo") String accountNo, @Param("amount") Double amount);
}
```

```
@FeignClient(value="seata-bank2",fallback= Bank2ClientFallback.class)
public interface Bank2Client {
    //远程调用李四的微服务
    @GetMapping("/bank2/transfer")
    public  String transfer(@RequestParam("amount") Double amount);
}
```

```
@Component
public class Bank2ClientFallback implements Bank2Client {
    @Override
    public String transfer(Double amount) {
        return "fallback";
    }
}
```

```
@Service
@Slf4j
public class AccountInfoServiceImpl implements AccountInfoService {
    @Autowired
    AccountInfoDao accountInfoDao;
    @Autowired
    Bank2Client bank2Client;
    @Transactional
    @GlobalTransactional//开启全局事务
    @Override
    public void updateAccountBalance(String accountNo, Double amount) {
        log.info("bank1 service begin,XID：{}", RootContext.getXID());
        //扣减张三的金额
        accountInfoDao.updateAccountBalance(accountNo,amount *-1);
        //调用李四微服务，转账
        String transfer = bank2Client.transfer(amount);
        if("fallback".equals(transfer)){
            //调用李四微服务异常
            throw new RuntimeException("调用李四微服务异常");
        }
        if(amount == 2){
            //人为制造异常
            throw new RuntimeException("bank1 make exception..");
        }
    }
}
```

将@GlobalTransactional注解标注在全局事务发起的Service实现方法上，开启全局事务： 

GlobalTransactionalInterceptor会拦截@GlobalTransactional注解的方法，生成全局事务ID(XID)，XID会在整个 

分布式事务中传递。在远程调用时，spring-cloud-alibaba-seata会拦截Feign调用将XID传递到下游服务。

## dtx-seata-bank2

李四账户增加金额。 dtx-seata-bank2在本账号事务中作为分支事务不使用@GlobalTransactional。 

```
@Mapper
@Component
public interface AccountInfoDao {
    //更新账户
    @Update("UPDATE account_info SET account_balance = account_balance + #{amount} WHERE account_no = #{accountNo}")
    int updateAccountBalance(@Param("accountNo") String accountNo, @Param("amount") Double amount);
}
```

```
@Service
@Slf4j
public class AccountInfoServiceImpl implements AccountInfoService {
    @Autowired
    AccountInfoDao accountInfoDao;
    @Transactional
    @Override
    public void updateAccountBalance(String accountNo, Double amount) {
        log.info("bank2 service begin,XID：{}",RootContext.getXID());
        //李四增加金额
        accountInfoDao.updateAccountBalance(accountNo,amount);
        if(amount==3){
            //人为制造异常
            throw new RuntimeException("bank2 make exception..");
        }
    }
}
```

启动dtx-seata-bank1，dtx-seata-bank2

http://127.0.0.1:56081/bank1/transfer?amount=2 张三转账出错回滚

http://127.0.0.1:56081/bank1/transfer?amount=3 李四收款出错回滚

由于Seata的0侵入性并且解决了传统2PC长期锁资源的问题，所以推荐采用Seata实现2PC。

Seata实现2PC要点： 

1、全局事务开始使用 @GlobalTransactional标识 。 

2、每个本地事务方案仍然使用@Transactional标识。 

3、每个数据都需要创建undo_log表，此表是seata保证本地事务一致性的关键。 

# Hmily实现TCC

导入bank1.sql,bank2.sql,创建account_info,local_try_log,local_confirm_log,local_cancel_log

```
CREATE DATABASE `hmily` CHARACTER SET 'utf8' COLLATE 'utf8_general_ci'; 
```

dtx/dtx-tcc/dtx-tcc-bank1 银行1，操作张三账户，连接数据库bank1 

dtx/dtx-tcc/dtx-tcc-bank2 银行2，操作李四账户，连接数据库bank2 

dtx-tcc引入maven依赖

```
<dependency>
    <groupId>org.dromara</groupId>
    <artifactId>hmily-springcloud</artifactId>
    <version>2.0.4-RELEASE</version>
</dependency>
```

配置hmily

```
org:
  dromara:
    hmily :
      serializer : kryo
      recoverDelayTime : 30
      retryMax : 30
      scheduledDelay : 30
      scheduledThreadMax :  10
      repositorySupport : db
      started: false
      hmilyDbConfig :
        driverClassName  : com.mysql.jdbc.Driver
        url :  jdbc:mysql://localhost:3306/hmily?useUnicode=true
        username : root
        password :
```

新增配置类接收application.yml中的Hmily配置信息,并增加@EnableAspectJAutoProxy(proxyTargetClass=true)

```
@Bean
public HmilyTransactionBootstrap hmilyTransactionBootstrap(HmilyInitService hmilyInitService){
    HmilyTransactionBootstrap hmilyTransactionBootstrap = new HmilyTransactionBootstrap(hmilyInitService);
    hmilyTransactionBootstrap.setSerializer(env.getProperty("org.dromara.hmily.serializer"));
    hmilyTransactionBootstrap.setRecoverDelayTime(Integer.parseInt(env.getProperty("org.dromara.hmily.recoverDelayTime")));
    hmilyTransactionBootstrap.setRetryMax(Integer.parseInt(env.getProperty("org.dromara.hmily.retryMax")));
    hmilyTransactionBootstrap.setScheduledDelay(Integer.parseInt(env.getProperty("org.dromara.hmily.scheduledDelay")));
    hmilyTransactionBootstrap.setScheduledThreadMax(Integer.parseInt(env.getProperty("org.dromara.hmily.scheduledThreadMax")));
    hmilyTransactionBootstrap.setRepositorySupport(env.getProperty("org.dromara.hmily.repositorySupport"));
    hmilyTransactionBootstrap.setStarted(Boolean.parseBoolean(env.getProperty("org.dromara.hmily.started")));
    HmilyDbConfig hmilyDbConfig = new HmilyDbConfig();
    hmilyDbConfig.setDriverClassName(env.getProperty("org.dromara.hmily.hmilyDbConfig.driverClassName"));
    hmilyDbConfig.setUrl(env.getProperty("org.dromara.hmily.hmilyDbConfig.url"));
    hmilyDbConfig.setUsername(env.getProperty("org.dromara.hmily.hmilyDbConfig.username"));
    hmilyDbConfig.setPassword(env.getProperty("org.dromara.hmily.hmilyDbConfig.password"));
    hmilyTransactionBootstrap.setHmilyDbConfig(hmilyDbConfig);
    return hmilyTransactionBootstrap;
}
```

启动类新增org.dromara.hmily的扫描项

```
@ComponentScan({"com.onejane.dtx.tcc.bank2","org.dromara.hmily"})
```

## dtx-tcc-bank1

```
try： 
	try幂等校验 
	try悬挂处理 
	检查余额是够扣减金额 
	扣减金额 
confirm：
	空 
cancel：
	cancel幂等校验 
	cancel空回滚处理 
	增加可用余额
```

**Dao**

```
@Mapper
@Component
public interface AccountInfoDao {
    @Update("update account_info set account_balance=account_balance - #{amount} where account_balance>=#{amount} and account_no=#{accountNo} ")
    int subtractAccountBalance(@Param("accountNo") String accountNo, @Param("amount") Double amount);

    @Update("update account_info set account_balance=account_balance + #{amount} where account_no=#{accountNo} ")
    int addAccountBalance(@Param("accountNo") String accountNo, @Param("amount") Double amount);


    /**
     * 增加某分支事务try执行记录
     * @param localTradeNo 本地事务编号
     * @return
     */
    @Insert("insert into local_try_log values(#{txNo},now());")
    int addTry(String localTradeNo);

    @Insert("insert into local_confirm_log values(#{txNo},now());")
    int addConfirm(String localTradeNo);

    @Insert("insert into local_cancel_log values(#{txNo},now());")
    int addCancel(String localTradeNo);

    /**
     * 查询分支事务try是否已执行
     * @param localTradeNo 本地事务编号
     * @return
     */
    @Select("select count(1) from local_try_log where tx_no = #{txNo} ")
    int isExistTry(String localTradeNo);
    /**
     * 查询分支事务confirm是否已执行
     * @param localTradeNo 本地事务编号
     * @return
     */
    @Select("select count(1) from local_confirm_log where tx_no = #{txNo} ")
    int isExistConfirm(String localTradeNo);

    /**
     * 查询分支事务cancel是否已执行
     * @param localTradeNo 本地事务编号
     * @return
     */
    @Select("select count(1) from local_cancel_log where tx_no = #{txNo} ")
    int isExistCancel(String localTradeNo);

}
```

try和cancel方法

```
@Service
@Slf4j
public class AccountInfoServiceImpl implements AccountInfoService {

    @Autowired
    AccountInfoDao accountInfoDao;

    @Autowired
    Bank2Client bank2Client;

    // 账户扣款，就是tcc的try方法

    /**
     *     try幂等校验
     *     try悬挂处理
     *     检查余额是够扣减金额
     *     扣减金额
     * @param accountNo
     * @param amount
     */
    @Override
    @Transactional
    //只要标记@Hmily就是try方法，在注解中指定confirm、cancel两个方法的名字
    @Hmily(confirmMethod="commit",cancelMethod="rollback")
    public void updateAccountBalance(String accountNo, Double amount) {
        //获取全局事务id
        String transId = HmilyTransactionContextLocal.getInstance().get().getTransId();
        log.info("bank1 try begin 开始执行...xid:{}",transId);
        //幂等判断 判断local_try_log表中是否有try日志记录，如果有则不再执行
        if(accountInfoDao.isExistTry(transId)>0){
            log.info("bank1 try 已经执行，无需重复执行,xid:{}",transId);
            return ;
        }

        //try悬挂处理，如果cancel、confirm有一个已经执行了，try不再执行
        if(accountInfoDao.isExistConfirm(transId)>0 || accountInfoDao.isExistCancel(transId)>0){
            log.info("bank1 try悬挂处理  cancel或confirm已经执行，不允许执行try,xid:{}",transId);
            return ;
        }

        //扣减金额
        if(accountInfoDao.subtractAccountBalance(accountNo, amount)<=0){
            //扣减失败
            throw new RuntimeException("bank1 try 扣减金额失败,xid:{}"+transId);
        }
        //插入try执行记录,用于幂等判断
        accountInfoDao.addTry(transId);

        //远程调用李四，转账
        if(!bank2Client.transfer(amount)){
            throw new RuntimeException("bank1 远程调用李四微服务失败,xid:{}"+transId);
        }
        if(amount == 2){
            throw new RuntimeException("人为制造异常,xid:{}"+transId);
        }
        log.info("bank1 try end 结束执行...xid:{}",transId);
    }

    //confirm方法
    @Transactional
    public void commit(String accountNo, Double amount){
        //获取全局事务id
        String transId = HmilyTransactionContextLocal.getInstance().get().getTransId();
        log.info("bank1 confirm begin 开始执行...xid:{},accountNo:{},amount:{}",transId,accountNo,amount);
    }



    /** cancel方法
     *     cancel幂等校验
     *     cancel空回滚处理
     *     增加可用余额
     * @param accountNo
     * @param amount
     */
    @Transactional
    public void rollback(String accountNo, Double amount){
        //获取全局事务id
        String transId = HmilyTransactionContextLocal.getInstance().get().getTransId();
        log.info("bank1 cancel begin 开始执行...xid:{}",transId);
        // cancel幂等校验
        if(accountInfoDao.isExistCancel(transId)>0){
            log.info("bank1 cancel 已经执行，无需重复执行,xid:{}",transId);
            return ;
        }
        //cancel空回滚处理，如果try没有执行，cancel不允许执行
        if(accountInfoDao.isExistTry(transId)<=0){
            log.info("bank1 空回滚处理，try没有执行，不允许cancel执行,xid:{}",transId);
            return ;
        }
        // 增加可用余额
        accountInfoDao.addAccountBalance(accountNo,amount);
        //插入一条cancel的执行记录
        accountInfoDao.addCancel(transId);
        log.info("bank1 cancel end 结束执行...xid:{}",transId);

    }

}
```

feignClient 

```
@FeignClient(value="tcc-bank2",fallback= Bank2ClientFallback.class)
public interface Bank2Client {
    //远程调用李四的微服务
    @GetMapping("/bank2/transfer")
    @Hmily
    public  Boolean transfer(@RequestParam("amount") Double amount);
}
```

## dtx-tcc-bank2

```
try： 
	空 
confirm：
	confirm幂等校验 
	正式增加金额 
cancel：
	空
```

**Dao** 

```
@Component
@Mapper
public interface AccountInfoDao {

    @Update("update account_info set account_balance=account_balance + #{amount} where  account_no=#{accountNo} ")
    int addAccountBalance(@Param("accountNo") String accountNo, @Param("amount") Double amount);


    /**
     * 增加某分支事务try执行记录
     * @param localTradeNo 本地事务编号
     * @return
     */
    @Insert("insert into local_try_log values(#{txNo},now());")
    int addTry(String localTradeNo);

    @Insert("insert into local_confirm_log values(#{txNo},now());")
    int addConfirm(String localTradeNo);

    @Insert("insert into local_cancel_log values(#{txNo},now());")
    int addCancel(String localTradeNo);

    /**
     * 查询分支事务try是否已执行
     * @param localTradeNo 本地事务编号
     * @return
     */
    @Select("select count(1) from local_try_log where tx_no = #{txNo} ")
    int isExistTry(String localTradeNo);
    /**
     * 查询分支事务confirm是否已执行
     * @param localTradeNo 本地事务编号
     * @return
     */
    @Select("select count(1) from local_confirm_log where tx_no = #{txNo} ")
    int isExistConfirm(String localTradeNo);

    /**
     * 查询分支事务cancel是否已执行
     * @param localTradeNo 本地事务编号
     * @return
     */
    @Select("select count(1) from local_cancel_log where tx_no = #{txNo} ")
    int isExistCancel(String localTradeNo);

}
```

**实现confirm方法**

```
@Service
@Slf4j
public class AccountInfoServiceImpl implements AccountInfoService {

    @Autowired
    AccountInfoDao accountInfoDao;

    @Override
    @Hmily(confirmMethod="confirmMethod", cancelMethod="cancelMethod")
    public void updateAccountBalance(String accountNo, Double amount) {
        //获取全局事务id
        String transId = HmilyTransactionContextLocal.getInstance().get().getTransId();
        log.info("bank2 try begin 开始执行...xid:{}",transId);
    }

    /**
     * confirm方法
     *     confirm幂等校验
     *     正式增加金额
     * @param accountNo
     * @param amount
     */
    @Transactional
    public void confirmMethod(String accountNo, Double amount){
        //获取全局事务id
        String transId = HmilyTransactionContextLocal.getInstance().get().getTransId();
        log.info("bank2 confirm begin 开始执行...xid:{}",transId);
        if(accountInfoDao.isExistConfirm(transId)>0){
            log.info("bank2 confirm 已经执行，无需重复执行...xid:{}",transId);
            return ;
        }
        //增加金额
        accountInfoDao.addAccountBalance(accountNo,amount);
        //增加一条confirm日志，用于幂等
        accountInfoDao.addConfirm(transId);
        log.info("bank2 confirm end 结束执行...xid:{}",transId);
    }



    /**
     * @param accountNo
     * @param amount
     */
    public void cancelMethod(String accountNo, Double amount){
        //获取全局事务id
        String transId = HmilyTransactionContextLocal.getInstance().get().getTransId();
        log.info("bank2 cancel begin 开始执行...xid:{}",transId);

    }

}
```

http://127.0.0.1:56081/bank1/transfer?amount=2

# RocketMQ实现可靠消息最终一致性

在bank1、bank2数据库中新增de_duplication，交易记录表(去重表)，用于交易幂等控制。

交互流程如下： 

1、Bank1向MQ Server发送转账消息 

2、Bank1执行本地事务，扣减金额 

3、Bank2接收消息，执行本地事务，添加金额 

[RocketMQ](https://archive.apache.org/dist/rocketmq/4.5.0/rocketmq-all-4.5.0-bin-release.zip)解压并启动nameserver

```
set ROCKETMQ_HOME=C:\Users\rocketmq-all-4.5.0-bin-release
bin>mqnamesrv.cmd
```

启动brocker

```
set ROCKETMQ_HOME=C:\Users\rocketmq-all-4.5.0-bin-release
bin>mqbroker.cmd -n 127.0.0.1:9876 autoCreateTopicEnable=true
```

dtx/dtx-txmsg/dtx-txmsg-bank1 ，操作张三账户，连接数据库bank1 

dtx/dtx-txmsg/dtx-txmsg-bank2 ，操作李四账户，连接数据库bank2 

在dtx父工程中指定了SpringBoot和SpringCloud版本 

```
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-dependencies</artifactId>
    <version>2.1.3.RELEASE</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>

<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-dependencies</artifactId>
    <version>Greenwich.RELEASE</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

在dtx-txmsg父工程中指定了rocketmq-spring-boot-starter的版本

```
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-spring-boot-starter</artifactId>
    <version>2.0.2</version>
</dependency>
```

配置rocketMQ 

```
rocketmq.producer.group = producer_bank2
rocketmq.name-server = 127.0.0.1:9876
```

## dtx-txmsg-bank1

1、张三扣减金额，提交本地事务。 

2、向MQ发送转账消息。 

**Dao**

```
@Mapper
@Component
public interface AccountInfoDao {
    @Update("update account_info set account_balance=account_balance+#{amount} where account_no=#{accountNo}")
    int updateAccountBalance(@Param("accountNo") String accountNo, @Param("amount") Double amount);


    @Select("select * from account_info where where account_no=#{accountNo}")
    AccountInfo findByIdAccountNo(@Param("accountNo") String accountNo);



    @Select("select count(1) from de_duplication where tx_no = #{txNo}")
    int isExistTx(String txNo);


    @Insert("insert into de_duplication values(#{txNo},now());")
    int addTx(String txNo);

}
```

**AccountInfoService**

```
@Service
@Slf4j
public class AccountInfoServiceImpl implements AccountInfoService {

    @Autowired
    AccountInfoDao accountInfoDao;

    @Autowired
    RocketMQTemplate rocketMQTemplate;


    //向mq发送转账消息
    @Override
    public void sendUpdateAccountBalance(AccountChangeEvent accountChangeEvent) {

        //将accountChangeEvent转成json
        JSONObject jsonObject =new JSONObject();
        jsonObject.put("accountChange",accountChangeEvent);
        String jsonString = jsonObject.toJSONString();
        //生成message类型
        Message<String> message = MessageBuilder.withPayload(jsonString).build();
        //发送一条事务消息
        /**
         * String txProducerGroup 生产组
         * String destination topic，
         * Message<?> message, 消息内容
         * Object arg 参数
         */
        rocketMQTemplate.sendMessageInTransaction("producer_group_txmsg_bank1","topic_txmsg",message,null);

    }

    //更新账户，扣减金额
    @Override
    @Transactional
    public void doUpdateAccountBalance(AccountChangeEvent accountChangeEvent) {
        //幂等判断
        if(accountInfoDao.isExistTx(accountChangeEvent.getTxNo())>0){
            return ;
        }
        //扣减金额
        accountInfoDao.updateAccountBalance(accountChangeEvent.getAccountNo(),accountChangeEvent.getAmount() * -1);
        //添加事务日志
        accountInfoDao.addTx(accountChangeEvent.getTxNo());
        if(accountChangeEvent.getAmount() == 3){
            throw new RuntimeException("人为制造异常");
        }
    }
}
```

**RocketMQLocalTransactionListener**

编写RocketMQLocalTransactionListener接口实现类，实现执行本地事务和事务回查两个方法。

```
@Component
@Slf4j
@RocketMQTransactionListener(txProducerGroup = "producer_group_txmsg_bank1")
public class ProducerTxmsgListener implements RocketMQLocalTransactionListener {

    @Autowired
    AccountInfoService accountInfoService;

    @Autowired
    AccountInfoDao accountInfoDao;

    //事务消息发送后的回调方法，当消息发送给mq成功，此方法被回调
    @Override
    @Transactional
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object o) {

        try {
            //解析message，转成AccountChangeEvent
            String messageString = new String((byte[]) message.getPayload());
            JSONObject jsonObject = JSONObject.parseObject(messageString);
            String accountChangeString = jsonObject.getString("accountChange");
            //将accountChange（json）转成AccountChangeEvent
            AccountChangeEvent accountChangeEvent = JSONObject.parseObject(accountChangeString, AccountChangeEvent.class);
            //执行本地事务，扣减金额
            accountInfoService.doUpdateAccountBalance(accountChangeEvent);
            //当返回RocketMQLocalTransactionState.COMMIT，自动向mq发送commit消息，mq将消息的状态改为可消费
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            e.printStackTrace();
            return RocketMQLocalTransactionState.ROLLBACK;
        }


    }

    //事务状态回查，查询是否扣减金额
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        //解析message，转成AccountChangeEvent
        String messageString = new String((byte[]) message.getPayload());
        JSONObject jsonObject = JSONObject.parseObject(messageString);
        String accountChangeString = jsonObject.getString("accountChange");
        //将accountChange（json）转成AccountChangeEvent
        AccountChangeEvent accountChangeEvent = JSONObject.parseObject(accountChangeString, AccountChangeEvent.class);
        //事务id
        String txNo = accountChangeEvent.getTxNo();
        int existTx = accountInfoDao.isExistTx(txNo);
        if(existTx>0){
            return RocketMQLocalTransactionState.COMMIT;
        }else{
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }
}
```

##  **dtx-txmsg-bank2**

1、监听MQ，接收消息。 

2、接收到消息增加账户金额。

**Service**

注意为避免消息重复发送，这里需要实现幂等。 

```
@Service
@Slf4j
public class AccountInfoServiceImpl implements AccountInfoService {

    @Autowired
    AccountInfoDao accountInfoDao;

    //更新账户，增加金额
    @Override
    @Transactional
    public void addAccountInfoBalance(AccountChangeEvent accountChangeEvent) {
        log.info("bank2更新本地账号，账号：{},金额：{}",accountChangeEvent.getAccountNo(),accountChangeEvent.getAmount());
        if(accountInfoDao.isExistTx(accountChangeEvent.getTxNo())>0){

            return ;
        }
        //增加金额
        accountInfoDao.updateAccountBalance(accountChangeEvent.getAccountNo(),accountChangeEvent.getAmount());
        //添加事务记录，用于幂等
        accountInfoDao.addTx(accountChangeEvent.getTxNo());
        if(accountChangeEvent.getAmount() == 4){
            throw new RuntimeException("人为制造异常");
        }
    }
}
```

**MQ监听类** 

```
@Component
@Slf4j
@RocketMQMessageListener(consumerGroup = "consumer_group_txmsg_bank2",topic = "topic_txmsg")
public class TxmsgConsumer implements RocketMQListener<String> {

    @Autowired
    AccountInfoService accountInfoService;

    //接收消息
    @Override
    public void onMessage(String message) {
        log.info("开始消费消息:{}",message);
        //解析消息
        JSONObject jsonObject = JSONObject.parseObject(message);
        String accountChangeString = jsonObject.getString("accountChange");
        //转成AccountChangeEvent
        AccountChangeEvent accountChangeEvent = JSONObject.parseObject(accountChangeString, AccountChangeEvent.class);
        //设置账号为李四的
        accountChangeEvent.setAccountNo("2");
        //更新本地账户，增加金额
        accountInfoService.addAccountInfoBalance(accountChangeEvent);

    }
}
```

可靠消息最终一致性就是保证消息从生产方经过消息中间件传递到消费方的一致性，本案例使用了RocketMQ作为 

消息中间件，RocketMQ主要解决了两个功能： 

1、本地事务与消息发送的原子性问题。 

2、事务参与方接收消息的可靠性，bank2接收消息失败，一直重试接收消息，幂等性。

可靠消息最终一致性事务适合执行周期长且实时性要求不高的场景。引入消息机制后，同步的事务操作变为基于消 息执行的异步操作, 避免了分布式事务中的同步阻塞操作的影响，并实现了两个服务的解耦。 

启动nameserver

```
set ROCKETMQ_HOME=C:\Users\rocketmq-all-4.5.0-bin-release
bin>mqnamesrv.cmd
```

启动brocker

```
set ROCKETMQ_HOME=C:\Users\rocketmq-all-4.5.0-bin-release
bin>mqbroker.cmd -n 127.0.0.1:9876 autoCreateTopicEnable=true
```

新增topic

```
set ROCKETMQ_HOME=C:\Users\rocketmq-all-4.5.0-bin-release
mqadmin.cmd updateTopic -n 127.0.0.1:9876 -b 127.0.0.1:10911 -t topic_txmsg
```



# RocketMQ最大努力通知

目标：采用MQ的ack机制就可以实现最大努力通知，发起通知方通过一定的机制最大努力将业务处理结果通知到接收方。 

具体包括： 

1、有一定的消息重复通知机制。 

因为接收通知方可能没有接收到通知，此时要有一定的机制对消息重复通知。 

2、消息校对机制。 

如果尽最大努力也没有通知到接收方，或者接收方消费消息后要再次消费，此时可由接收方主动向通知方查询消息 信息来满足需求。 

**最大努力通知与可靠消息一致性有什么不同？** 

1、解决方案思想不同 

可靠消息一致性，发起通知方需要保证将消息发出去，并且将消息发到接收通知方，消息的可靠性关键由发起通知 方来保证。 

最大努力通知，发起通知方尽最大的努力将业务处理结果通知为接收通知方，但是可能消息接收不到，此时需要接 收通知方主动调用发起通知方的接口查询业务处理结果，通知的可靠性关键在接收通知方。 

2、两者的业务应用场景不同 

可靠消息一致性关注的是交易过程的事务一致，以异步的方式完成交易。 

最大努力通知关注的是交易后的通知事务，即将交易结果可靠的通知出去。 

3、技术解决方向不同 

可靠消息一致性要解决消息从发出到接收的一致性，即消息发出并且被接收到。 

最大努力通知无法保证消息从发出到接收的一致性，只提供消息接收的可靠性机制。可靠机制是，最大努力的将消 息通知给接收方，当消息无法被接收方接收时，由接收方主动查询消息（业务处理结果）。 

**方案1**

![image-20201203211712992](upload\image-20201203211712992.png)

1、发起方将通知发给MQ。 

注意：如果消息没有发出去可由接收通知方主动请求发起通知方查询业务执行结果。

2、接收通知方监听 MQ。 

3、接收方接收消息，业务处理完成回应ack。 

4、接收方若没有回应ack则MQ会重复通知。 

MQ会**按照间隔1min、5min、10min、30min、1h、2h、5h、10h的方式，逐步拉大通知间隔** （如果MQ采用 

rocketMq，在broker中可进行配置），直到达到通知要求的时间窗口上限。 

5、接收方可通过消息校对接口来校对消息的一致性

**方案2**

![image-20201203212054036](upload\image-20201203212054036.png)

1、发起通知方将通知发给MQ。 

使用可靠消息一致方案中的事务消息保证本地事务与消息的原子性，最终将通知先发给MQ。 

2、通知程序监听 MQ，接收MQ的消息。 

方案1中接收方直接监听MQ，方案2中由通知程序监听MQ。通知程序若没有回应ack则MQ会重复通知。 

3、通知程序通过互联网接口协议（如http、webservice）调用接收通知方案接口，完成通知。 

通知程序调用接收通知方案接口成功就表示通知成功，即消费MQ消息成功，MQ将不再向通知程序投递通知消 

息。

4、接收方可通过消息校对接口来校对消息的一致性。 

**方案1和方案2的不同点：** 

1、方案1中接收方与MQ接口，即接收通知方案监听 MQ，此方案主要应用与内部应用之间的通知。 

2、方案2中由通知程序与MQ接口，通知程序监听MQ，收到MQ的消息后由通知程序通过互联网接口协议调用接收方。此方案主要应用于外部应用之间的通知，例如支付宝、微信的支付结果通知。 

![image-20201203212353311](upload\image-20201203212353311.png)

充值交互流程如下：

1、用户请求充值系统进行充值。 

2、充值系统完成充值将充值结果发给MQ。 

3、账户系统监听MQ，接收充值结果通知，如果接收不到消息，MQ会重复发送通知。接收到充值结果通知账户系 

统增加充值金额。 

4、账户系统也可以主动查询充值系统的充值结果查询接口，增加金额

dtx/dtx-notifymsg/dtx-notifymsg-bank1 银行1，操作张三账户， 连接数据库bank1 

dtx/dtx-notifymsg/dtx-notifymsg-pay 银行2，操作充值记录，连接数据库bank1_pay

创建bank1_pay.account_pay

启动RocketMQ

启动nameserver

```
set ROCKETMQ_HOME=C:\Users\rocketmq-all-4.5.0-bin-release
bin>mqnamesrv.cmd
```

启动brocker

```
set ROCKETMQ_HOME=C:\Users\rocketmq-all-4.5.0-bin-release
bin>mqbroker.cmd -n 127.0.0.1:9876 autoCreateTopicEnable=true
```

新增topic

```
set ROCKETMQ_HOME=C:\Users\rocketmq-all-4.5.0-bin-release
mqadmin.cmd updateTopic -n 127.0.0.1:9876 -b 127.0.0.1:10911 -t topic_txmsg
```

在dtx父工程中指定了SpringBoot和SpringCloud版本

```
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-dependencies</artifactId>
    <version>2.1.3.RELEASE</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>

<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-dependencies</artifactId>
    <version>Greenwich.RELEASE</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

在dtx-notifymsg父工程中指定了rocketmq-spring-boot-starter的版本

```
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-spring-boot-starter</artifactId>
    <version>2.0.2</version>
</dependency>
```

在application-local.propertis中配置rocketMQ nameServer地址及生产组： 

```
rocketmq.producer.group = producer_notifymsg_bank1
rocketmq.name-server = 127.0.0.1:9876
```

## dtx-notifymsg-pay

1、充值接口 

2、充值完成要通知 

3、充值结果查询接口 

**Dao**

```
@Mapper
@Component
public interface AccountPayDao {
    @Insert("insert into account_pay(id,account_no,pay_amount,result) values(#{id},#{accountNo},#{payAmount},#{result})")
    int insertAccountPay(@Param("id") String id, @Param("accountNo") String accountNo, @Param("payAmount") Double pay_amount, @Param("result") String result);

    @Select("select id,account_no accountNo,pay_amount payAmount,result from account_pay where id=#{txNo}")
    AccountPay findByIdTxNo(@Param("txNo") String txNo);
}
```

**Service** 

```
@Service
@Slf4j
public class AccountPayServiceImpl implements AccountPayService {

    @Autowired
    AccountPayDao accountPayDao;

    @Autowired
    RocketMQTemplate rocketMQTemplate;

    //插入充值记录
    @Override
    public AccountPay insertAccountPay(AccountPay accountPay) {
        int success = accountPayDao.insertAccountPay(accountPay.getId(), accountPay.getAccountNo(), accountPay.getPayAmount(), "success");
        if(success>0){
            //发送通知,使用普通消息发送通知
            accountPay.setResult("success");
            rocketMQTemplate.convertAndSend("topic_notifymsg",accountPay);
            return accountPay;
        }
        return null;
    }

    //查询充值记录，接收通知方调用此方法来查询充值结果
    @Override
    public AccountPay getAccountPay(String txNo) {
        AccountPay accountPay = accountPayDao.findByIdTxNo(txNo);
        return accountPay;
    }
}
```

**Controller**

```
@RestController
public class AccountPayController {

    @Autowired
    AccountPayService accountPayService;

    //充值
    @GetMapping(value = "/paydo")
    public AccountPay pay(AccountPay accountPay){
        //生成事务编号
        String txNo = UUID.randomUUID().toString();
        accountPay.setId(txNo);
        return accountPayService.insertAccountPay(accountPay);
    }

    //查询充值结果
    @GetMapping(value = "/payresult/{txNo}")
    public AccountPay payresult(@PathVariable("txNo") String txNo){
        return accountPayService.getAccountPay(txNo);
    }
}
```

## **dtx-notifymsg-bank1**

1、监听MQ，接收充值结果，根据充值结果完成账户金额修改。 

2、主动查询充值系统，根据充值结果完成账户金额修改。 

**Dao**

```
@Mapper
@Component
public interface AccountInfoDao {
    //修改账户金额
    @Update("update account_info set account_balance=account_balance+#{amount} where account_no=#{accountNo}")
    int updateAccountBalance(@Param("accountNo") String accountNo, @Param("amount") Double amount);


   //查询幂等记录，用于幂等控制
    @Select("select count(1) from de_duplication where tx_no = #{txNo}")
    int isExistTx(String txNo);

    //添加事务记录，用于幂等控制
    @Insert("insert into de_duplication values(#{txNo},now());")
    int addTx(String txNo);

}
```

**AccountInfoService**

```
@Service
@Slf4j
public class AccountInfoServiceImpl implements AccountInfoService {

    @Autowired
    AccountInfoDao accountInfoDao;

    @Autowired
    PayClient payClient;

    //更新账户金额
    @Override
    @Transactional
    public void updateAccountBalance(AccountChangeEvent accountChange) {
        //幂等校验
        if(accountInfoDao.isExistTx(accountChange.getTxNo())>0){
            return ;
        }
        int i = accountInfoDao.updateAccountBalance(accountChange.getAccountNo(), accountChange.getAmount());
        //插入事务记录，用于幂等控制
        accountInfoDao.addTx(accountChange.getTxNo());
    }

    //远程调用查询充值结果
    @Override
    public AccountPay queryPayResult(String tx_no) {

        //远程调用
        AccountPay payresult = payClient.payresult(tx_no);
        if("success".equals(payresult.getResult())){
            //更新账户金额
            AccountChangeEvent accountChangeEvent = new AccountChangeEvent();
            accountChangeEvent.setAccountNo(payresult.getAccountNo());//账号
            accountChangeEvent.setAmount(payresult.getPayAmount());//金额
            accountChangeEvent.setTxNo(payresult.getId());//充值事务号
            updateAccountBalance(accountChangeEvent);
        }
        return payresult;
    }
}
```

```
@FeignClient(value = "dtx-notifymsg-pay",fallback = PayFallback.class)
public interface PayClient {

    //远程调用充值系统的接口查询充值结果
    @GetMapping(value = "/pay/payresult/{txNo}")
    public AccountPay payresult(@PathVariable("txNo") String txNo);
}
```

```
@Component
public class PayFallback implements PayClient {
    @Override
    public AccountPay payresult(String txNo) {
        AccountPay accountPay = new AccountPay();
        accountPay.setResult("fail");
        return accountPay;
    }
}
```

**监听MQ**

```
@Component
@Slf4j
@RocketMQMessageListener(topic = "topic_notifymsg",consumerGroup = "consumer_group_notifymsg_bank1")
public class NotifyMsgListener implements RocketMQListener<AccountPay> {

    @Autowired
    AccountInfoService accountInfoService;

    //接收消息
    @Override
    public void onMessage(AccountPay accountPay) {
        log.info("接收到消息：{}", JSON.toJSONString(accountPay));
        if("success".equals(accountPay.getResult())){
            //更新账户金额
            AccountChangeEvent accountChangeEvent = new AccountChangeEvent();
            accountChangeEvent.setAccountNo(accountPay.getAccountNo());
            accountChangeEvent.setAmount(accountPay.getPayAmount());
            accountChangeEvent.setTxNo(accountPay.getId());
            accountInfoService.updateAccountBalance(accountChangeEvent);
        }
        log.info("处理消息完成：{}", JSON.toJSONString(accountPay));
    }
}
```

**Controller** 

```
@RestController
@Slf4j
public class AccountInfoController {

    @Autowired
    private AccountInfoService accountInfoService;

    //主动查询充值结果
    @GetMapping(value = "/payresult/{txNo}")
    public AccountPay result(@PathVariable("txNo") String txNo){
        AccountPay accountPay = accountInfoService.queryPayResult(txNo);
        return accountPay;
    }
}
```

充值成功不发消息，主动查询。

最大努力通知方案是分布式事务中对一致性要求最低的一种,适用于一些最终一致性时间敏感度低的业务； 

最大努力通知方案需要实现如下功能： 

1、消息重复通知机制。 

2、消息校对机制。

# 注册账号

采用用户、账号分离设计(这样设计的好处是，当用户的业务信息发生变化时，不会影响的认证、授权等系统机 制)，因此需要保证用户信息与账号信息的一致性。

![image-20201204112527665](upload\image-20201204112527665.png)

用户向用户中心发起注册请求，用户中心保存用户业务信息，然后通知统一账号服务新建该用户所对应登录账号。

针对注册业务，如果用户与账号信息不一致，则会导致严重问题，因此该业务对一致性要求较为严格，即当用户服 务和账号服务任意一方出现问题都需要回滚事务。 

1、采用可靠消息一致性方案 

可靠消息一致性要求只要消息发出，事务参与者接到消息就要将事务执行成功，不存在回滚的要求，所以不适用。

2、采用最大努力通知方案 

最大努力通知表示发起通知方执行完本地事务后将结果通知给事务参与者，即使事务参与者执行业务处理失败发起 通知方也不会回滚事务，所以不适用。

3、采用Seata实现2PC 

在用户中心发起全局事务，统一账户服务为事务参与者，用户中心和统一账户服务只要有一方出现问题则全局事务 回滚，符合要求。 

实现方法如下： 

​	1、用户中心添加用户信息，开启全局事务 

​	2、统一账号服务添加账号信息，作为事务参与者 

​	3、其中一方执行失败Seata对SQL进行逆操作删除用户信息和账号信息，实现回滚。 

4、采用Hmily实现TCC 

TCC也可以实现用户中心和统一账户服务只要有一方出现问题则全局事务回滚，符合要求。 

实现方法如下： 

1、用户中心 

​	try：添加用户，状态为不可用 

​	confirm：更新用户状态为可用

​	cancel：删除用户 

2、统一账号服务 

​	try：添加账号，状态为不可用 

​	confirm：更新账号状态为可用 

​	cancel：删除账号 

# 存管开户

根据政策要求，P2P业务必须让银行存管资金，用户的资金在银行存管系统的账户中，而不在P2P平台中，因此用 户要在银行存管系统开户。

![image-20201204113059713](upload\image-20201204113059713.png)

用户向用户中心提交开户资料，用户中心生成开户请求号并重定向至银行存管系统开户页面。用户设置存管密码并 确认开户后，银行存管立即返回“请求已受理”。在某一时刻，银行存管系统处理完该开户请求后，将调用回调地址 通知处理结果，若通知失败，则按一定策略重试通知。同时，银行存管系统应提供**开户结果查询**的接口，供用户中 心校对结果。 

P2P平台的用户中心与银行存管系统之间属于跨系统交互，银行存管系统属于外部系统，用户中心无法干预银行存 管系统，所以用户中心只能在收到银行存管系统的业务处理结果通知后积极处理，开户后的使用情况完全由用户中 心来控制。 

1、采用Seata实现2PC 

需要侵入银行存管系统的数据库，由于它的外部系统，所以不适用。 

2、采用Hmily实现TCC 

TCC侵入性更强，所以不适用。 

3、基于MQ的可靠消息一致性 

如果让银行存管系统监听 MQ则不合适 ，因为它的外部系统。 如果银行存管系统将消息发给MQ用户中心监听MQ是可以的，但是由于相对银行存管系统来说用户中心属于外部 系统，银行存管系统是不会让外部系统直接监听自己的MQ的，基于MQ的通信协议也不方便外部系统间的交互， 所以本方案不合适。 

4、最大努力通知方案 

银行存管系统内部使用MQ，银行存管系统处理完业务后将处理结果发给MQ，由银行存管的通知程序专门发送通 知，并且采用互联网协议通知给第三方系统（用户中心）。

![image-20201204113219729](upload\image-20201204113219729.png)

# **满标审核** 

在借款人标的募集够所有的资金后，P2P运营管理员审批该标的，触发放款，并开启还款流程。

![image-20201204113249592](upload\image-20201204113249592.png)

管理员对某标的满标审批通过，交易中心修改标的状态为“还款中”，同时要通知还款服务生成还款计划。

生成还款计划是一个执行时长较长的业务，不建议阻塞主业务流程，此业务对一致性要求较低。 

根据上述需求进行解决方案分析： 

1、采用Seata实现2PC 

Seata在事务执行过程会进行数据库资源锁定，由于事务执行时长较长会将资源锁定较长时间，所以不适用。 

2、采用Hmily实现TCC 

本需求对业务一致性要求较低，因为生成还款计划的时长较长，所以不要求交易中心修改标的状态为“还款中”就立 即生成还款计划 ，所以本方案不适用。 

3、基于MQ的可靠消息一致性 

满标审批通过后由交易中心修改标的状态为“还款中”并且向还款服务发送消息，还款服务接收到消息开始生成还款 计划，基本于MQ的可靠消息一致性方案适用此场景 。 

4、最大努力通知方案 

满标审批通过后由交易中心向还款服务发送通知要求生成还款计划，还款服务并且对外提供还款计划生成结果校对 接口供其它服务查询，最大努力 通知方案也适用本场景 。

**分布式事务对比分析**

在学习各种分布式事务的解决方案后，我们了解到各种方案的优缺点： 

**2PC** 最大的诟病是一个阻塞协议。RM在执行分支事务后需要等待TM的决定，此时服务会阻塞并锁定资源。由于其阻塞机制和最差时间复杂度高， 因此，这种设计不能适应随着事务涉及的服务数量增加而扩展的需要，很难用于并发较高以及子事务生命周期较长 (long-running transactions) 的分布式服务中。 

**TCC**事务的处理流程与2PC两阶段提交做比较，2PC通常都是在跨库的DB层面，而TCC则在应用层面的处 理，需要通过业务逻辑来实现。这种分布式事务的实现方式的优势在于，可以让**应用自己定义数据操作的粒度，使** **得降低锁冲突、提高吞吐量成为可能**。而不足之处则在于对应用的侵入性非常强，业务逻辑的每个分支都需要实现 try、confifirm、cancel三个操作。此外，其实现难度也比较大，需要按照网络状态、系统故障等不同的失败原因实现不同的回滚策略。典型的使用场景：满，登录送优惠券等。 

**可靠消息最终一致性**事务适合执行周期长且实时性要求不高的场景。引入消息机制后，同步的事务操作变为基于消 息执行的异步操作, 避免了分布式事务中的同步阻塞操作的影响，并实现了两个服务的解耦。典型的使用场景：注 册送积分，登录送优惠券等。 

**最大努力通知**是分布式事务中要求最低的一种,适用于一些最终一致性时间敏感度低的业务；允许发起通知方处理业务失败，在接收通知方收到通知后积极进行失败处理，无论发起通知方如何处理结果都会不影响到接收通知方的后续处理；发起通知方需提供查询执行情况接口，用于接收通知方校对结果。典型的使用场景：银行通知、支付结果通知等

![image-20201204113807503](upload\image-20201204113807503.png)

在条件允许的情况下，我们尽可能选择本地事务单数据源，因为它减少了网络交互带来的性能损耗，且避免了数据 弱一致性带来的种种问题。