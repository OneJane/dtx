# Seata
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

