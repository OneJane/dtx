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











