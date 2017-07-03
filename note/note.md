# 连接

我们仍以常用的方式建立数据库连接，如下代码所示:

```java
@Before
public void init() throws ClassNotFoundException, SQLException {
    Class.forName("com.mysql.jdbc.Driver");
    connection = DriverManager.getConnection(
        "jdbc:mysql://localhost:3306/test", "tiger", "tiger");
}
```

## 驱动注册

当mysql驱动类被加载时，会向java.sql.DriverManager进行注册，Driver静态初始化源码:

```java
static {
    java.sql.DriverManager.registerDriver(new Driver());
}
```

DriverManager.registerDriver:

```java
public static synchronized void registerDriver(java.sql.Driver driver,
    DriverAction da) {
    /* Register the driver if it has not already been added to our list */
    if(driver != null) {
        registeredDrivers.addIfAbsent(new DriverInfo(driver, da));
    }
}
```

registeredDrivers其实是一个CopyOnWriteArrayList类型，DriverAction用于当驱动被取消注册时被调用，DriverInfo是DriverManager的内部类，其实就是对Driver和DriverAction对象进行了一次包装，并没有其它的作用。

接下来看一下驱动接口Driver的定义，位于java.sql包下，类图:

![Driver](images/Driver.jpg)

DriverManager.getConnection方法调用了NonRegisteringDriver的connect方法:

```java
public java.sql.Connection connect(String url, Properties info) {
    if (url != null) {
        if (StringUtils.startsWithIgnoreCase(url, LOADBALANCE_URL_PREFIX)) {
            return connectLoadBalanced(url, info);
        } else if (StringUtils.startsWithIgnoreCase(url, REPLICATION_URL_PREFIX)) {
            return connectReplicationConnection(url, info);
        }
    }
    Properties props = null;
    if ((props = parseURL(url, info)) == null) {
        return null;
    }
    if (!"1".equals(props.getProperty(NUM_HOSTS_PROPERTY_KEY))) {
        return connectFailover(url, info);
    }
    Connection newConn = com.mysql.jdbc.ConnectionImpl.
        getInstance(host(props), port(props), props, database(props), url);
    return newConn;
}
```

## 建立

从源码中可以看出，系统针对URL的不同采用了不同的连接策略，对于以jdbc:mysql:loadbalance://开头的URL，便以Master/Slave的架构进行连接，对于以jdbc:mysql:replication://开头的URL便按照双主的架构进行连接，如果就是我们使用的普通的URL，那么检测URL中节点的数量，如果大于1，那么使用failOver的方式，最后才是我们的测试代码中单节点的连接方式。

关于以上提到的Mysql两种集群模式，可以参考:

[Mysql之主从架构的复制原理及主从/双主配置详解(二)](http://blog.csdn.net/sz_bdqn/article/details/46277831)

parseURL方法用于解析URL中的各个属性，下面是对于默认URL解析之后得到的结果截图:

![URL解析](images/parse_url.PNG)

ConnectionImpl.getInstance方法对当前jdbc版本进行了区分:

```java
protected static Connection getInstance(String hostToConnectTo, int portToConnectTo,
    Properties info, String databaseToConnectTo, String url){
    if (!Util.isJdbc4()) {
        return new ConnectionImpl(hostToConnectTo, portToConnectTo, info, databaseToConnectTo, url);
    }
    return (Connection) Util.handleNewInstance(JDBC_4_CONNECTION_CTOR,
        new Object[] { hostToConnectTo, Integer.valueOf(portToConnectTo),
        info, databaseToConnectTo, url }, null);
}
```

驱动通过判断当前classpath下是否存在java.sql.NClob来决定是否是jdbc4版本，子jdk6开始自带的便是jdbc4版本，各个版本之间的区别在于高版本提供更多的接口实现，接下来都以jdbc4版本进行说明。

handleNewInstance方法所做的其实就是利用反射的方法构造了一个com.mysql.jdbc.JDBC4Connection的对象，后面的Object数组便是构造器的参数。

这里就涉及到了jdbc里的另一个核心接口: Connection:

![Connection](images/Connection.jpg)

核心连接逻辑位于ConnectionImpl的构造器中，其核心逻辑(简略版源码)如下:

```java
public ConnectionImpl(...) {
    initializeDriverProperties(info);
    initializeSafeStatementInterceptors();
    createNewIO(false);
    unSafeStatementInterceptors();
}
```

下面分部分对其进行说明。

### 属性解析

info是一个Properties对象，由jdbc连接url解析而来，Mysql的url允许我们进行参数的传递，对于我们普通的没有参数的url: jdbc:mysql://localhost:3306/test，解析得到的属性对象如下图:

![URL属性](images/info.png)

从上面类图中可以看出，ConnectionImpl其实是ConnectionPropertiesImpl的子类，而**ConnectionPropertiesImpl正是连接参数的载体**，所以initializeDriverProperties方法的目的可以总结如下:

- 将我们通过URL传入的参数设置到ConnectionPropertiesImpl的相应Field中去，以待后续进行连接时使用。
- 根据我们传入的以及默认的参数对相应的数据结构进行初始化。

initializeDriverProperties首先调用了父类的initializeProperties方法，用以实现第一个目的，简略版源码:

```java
protected void initializeProperties(Properties info) throws SQLException {
    if (info != null) {
        Properties infoCopy = (Properties) info.clone();
        int numPropertiesToSet = PROPERTY_LIST.size();
         for (int i = 0; i < numPropertiesToSet; i++) {
            java.lang.reflect.Field propertyField = PROPERTY_LIST.get(i);
            ConnectionProperty propToSet = (ConnectionProperty) propertyField.get(this);
            propToSet.initializeFrom(infoCopy, getExceptionInterceptor());
         }
    }
}
```

ConnectionPropertiesImpl中的配置字段其实都是ConnectionProperty(定义在其内部)类型:

![ConnectionProperty](images/ConnectionProperty.jpg)

下面是这种属性的典型定义方式:

```java
private IntegerConnectionProperty loadBalanceAutoCommitStatementThreshold = new IntegerConnectionProperty
    ("loadBalanceAutoCommitStatementThreshold", 0, 0,
    Integer.MAX_VALUE, Messages.getString("ConnectionProperties.loadBalanceAutoCommitStatementThreshold"),
     "5.1.15", MISC_CATEGORY, Integer.MIN_VALUE);
```

PROPERTY_LIST其实就是用反射的方法得到的ConnectionPropertiesImpl中所有ConnectionProperty类型Field集合，定义以及初始化源码如下:

```java
private static final ArrayList<Field> PROPERTY_LIST = new ArrayList<>();
static {
    java.lang.reflect.Field[] declaredFields = ConnectionPropertiesImpl.class.getDeclaredFields();
    for (int i = 0; i < declaredFields.length; i++) {
        if (ConnectionPropertiesImpl.ConnectionProperty.class.isAssignableFrom(declaredFields[i].getType())) {
            PROPERTY_LIST.add(declaredFields[i]);
        }
    }
}
```

initializeFromfan方法所做的正如其方法名，就是从属性对象中检测有没有和自己相匹配的设置项，如果有，那么更新为我们设置的值，否则使用默认值。

ConnectionProperty.initializeFrom:

```java
void initializeFrom(Properties extractFrom, ExceptionInterceptor exceptionInterceptor) {
    String extractedValue = extractFrom.getProperty(getPropertyName());
    extractFrom.remove(getPropertyName());
    initializeFrom(extractedValue, exceptionInterceptor);
}
```

以StringConnectionProperty为例，接收(String, ExceptionInterceptor)参数的initializeFrom方法实现如下:

```java
@Override
void initializeFrom(String extractedValue, ExceptionInterceptor exceptionInterceptor) {
    if (extractedValue != null) {
        validateStringValues(extractedValue, exceptionInterceptor);
        this.valueAsObject = extractedValue;
    } else {
        //使用默认值
        this.valueAsObject = this.defaultValue;
    }
    this.updateCount++;
}
```

从上面类图中可以看到，ConnectionProperty中有一个allowableValues字段，对于StringConnectionProperty来说validateStringValues的逻辑很简单，就是依次遍历整个allowableValues数组，检查给定的设置值是否在允许的范围内，核心源码如下:

```java
for (int i = 0; i < validateAgainst.length; i++) {
    if ((validateAgainst[i] != null) && validateAgainst[i].equalsIgnoreCase(valueToValidate)) {
        //检查通过
        return;
    }
}
```

### 异常拦截器

initializeDriverProperties方法相关源码:

```java
String exceptionInterceptorClasses = getExceptionInterceptors();
if (exceptionInterceptorClasses != null && !"".equals(exceptionInterceptorClasses)) {
    this.exceptionInterceptor = new ExceptionInterceptorChain(exceptionInterceptorClasses);
}
```

很容易想到，getExceptionInterceptors方法获取的其实是父类中定义的exceptionInterceptors属性:

```java
private StringConnectionProperty exceptionInterceptors = new StringConnectionProperty("exceptionInterceptors", null,
    Messages.getString("ConnectionProperties.exceptionInterceptors"), "5.1.8", MISC_CATEGORY, Integer.MIN_VALUE);
```

也就是说我们可以通过给URL传入exceptionInterceptors参数以定义我们自己的异常处理器，并且是从Mysql 5.1.8版本才开始支持，这其实为我们留下了一个扩展点: 可以不修改业务代码从而对Mysql驱动的运行状态进行监控。只找到了下面一篇简单的介绍文章:

[Connector/J extension points – exception interceptors](http://mysqlblog.fivefarmers.com/2011/11/21/connectorj-extension-points-%E2%80%93-exception-interceptors/)

所有的异常拦截器必须实现ExceptionInterceptor接口，这里要吐槽一下，这个接口竟然一行注释也没有！

![ExceptionInterceptor](images/ExceptionInterceptor.jpg)

ExceptionInterceptorChain其实是装饰模式的体现，内部有一个拦截器列表:

```java
List<Extension> interceptors;
```

其interceptException方法便是遍历此列表依次调用所有拦截器的interceptException方法。

#### 初始化

由ExceptionInterceptorChain的构造器调用Util.loadExtensions方法完成:

```java
public static List<Extension> loadExtensions(Connection conn, Properties props, String extensionClassNames, String errorMessageKey,
            ExceptionInterceptor exceptionInterceptor) {
    List<Extension> extensionList = new LinkedList<Extension>();
    List<String> interceptorsToCreate = StringUtils.split(extensionClassNames, ",", true);
    String className = null;
    for (int i = 0, s = interceptorsToCreate.size(); i < s; i++) {
        className = interceptorsToCreate.get(i);
        Extension extensionInstance = (Extension) Class.forName(className).newInstance();
        extensionInstance.init(conn, props);
        extensionList.add(extensionInstance);
    }
    return extensionList;
}
```

从这里可以看出以下几点:

- exceptionInterceptors参数可以同时指定多个拦截器，之间以逗号分隔。
- 拦截器指定时必须用完整的类名。
- 按照我们传入的参数的顺序进行调用。

### 国际化

从上面配置项的定义可以看出，Mysql使用了Messages类对消息进行了处理，这里Messages其实是对jdk国际化支持ResourceBundle的一层包装，下面是其简略版源码:

```java
public class Messages {
    private static final String BUNDLE_NAME = "com.mysql.jdbc.LocalizedErrorMessages";
    private static final ResourceBundle RESOURCE_BUNDLE;
    public static String getString(String key) {
        return RESOURCE_BUNDLE.getString(key);
    }
}
```

这里省略了资源加载的过程，什么是国际化，问度娘就好了。

### 日志记录

对应initializeDriverProperties方法的下列源码:

```java
if (getProfileSql() || getUseUsageAdvisor()) {
    this.eventSink = ProfilerEventHandlerFactory.getInstance(getMultiHostSafeProxy());
}
```

getProfileSql方法对应URL的profileSQL参数，getUseUsageAdvisor对应useUsageAdvisor参数，两个参数默认为false，即关闭，profileSQL如果打开，那么Mysql将会把sql执行的相应日志记录下来，用于性能分析，参见:

[Enabling MySQL general query log with JDBC](https://stackoverflow.com/questions/10903206/enabling-mysql-general-query-log-with-jdbc)

useUsageAdvisor参数用以记录Mysql认为性能不高的查询操作。

eventSink是一个ProfilerEventHandler对象:

![ProfilerEventHandler](images/ProfilerEventHandler.jpg)

getMultiHostSafeProxy方法用于在负载均衡的情况下获得代理对象，如果使用了负载均衡，那么必定有多台真实的Mysql物理机，所以在这种情况下连接就变成一个逻辑上的概念。

下面看一下ProfilerEventHandlerFactory.getInstance的实现:

```java
public static synchronized ProfilerEventHandler getInstance(MySQLConnection conn) throws SQLException {
    //这里获取的就是ConnectionImpl内部的eventSink，第一次当然为null
    ProfilerEventHandler handler = conn.getProfilerEventHandlerInstance();
    if (handler == null) {
        handler = (ProfilerEventHandler) Util.getInstance(conn.getProfilerEventHandler(),
            new Class[0], new Object[0], conn.getExceptionInterceptor());
        conn.initializeExtension(handler);
        conn.setProfilerEventHandlerInstance(handler);
    }
    return handler;
}
```

Util.getInstance方法根据传入的完整类名用反射的方式初始化一个对象并返回，完整类名便是conn.getProfilerEventHandler()方法获得的在ConnectionPropertiesImpl中定义的profilerEventHandler参数，默认值便是com.mysql.jdbc.profiler.LoggingProfilerEventHandler，不过从这里我们也可以看出，Mysql为我们留下了扩展的机会。

LoggingProfilerEventHandler的实现非常简单:

```java
public class LoggingProfilerEventHandler implements ProfilerEventHandler {
    private Log log;
    public void consumeEvent(ProfilerEvent evt) {
        if (evt.eventType == ProfilerEvent.TYPE_WARN) {
            this.log.logWarn(evt);
        } else {
            this.log.logInfo(evt);
        }
    }
    public void destroy() {
        this.log = null;
    }
    //被上面的initializeExtension方法调用
    public void init(Connection conn, Properties props) throws SQLException {
        this.log = conn.getLog();
    }
}
```

#### ProfilerEvent

![ProfilerEvent](images/ProfilerEvent.jpg)

推测: 此类必定是检测事件对象的序列化与反序列化的载体。

### 预编译缓存

所谓的"预编译"指的便是jdbc标准里面的PreparedStatement：

![PreparedStatement](images/PreparedStatement.jpg)

注意，Statement和PreparedStatement的类图并未画全，否则实在是太长了。:cry:

Statement在jdbc里代表的便是一条sql语句的执行，而这里的编译指的是什么将在后面提到。参数cachePrepStmts 如果设为true，那么jdbc便会将编译之后得到的PreparedStatement对象缓存起来，**当在一个连接内**多次针对同一条sql语句调用`connection.prepareStatement(sql)`方法时返回的实际上是一个PreparedStatement对象。这在数据库连接池中非常有用，默认是关闭的。

预编译的好处共有两点:

- 减轻Mysql服务的负担，这不废话么。
- **抵御SQL注入攻击**，

如果我们开启了此参数，那么Mysql jdbc将为其建立相应的缓存数据结构，initializeDriverProperties方法相应源码:

```java
if (getCachePreparedStatements()) {
    createPreparedStatementCaches();
}
```

createPreparedStatementCaches简略版源码:

```java
private void createPreparedStatementCaches() throws SQLException {
    synchronized (getConnectionMutex()) {
        //默认25
        int cacheSize = getPreparedStatementCacheSize();
        //1.
        Class<?> factoryClass = Class.forName(getParseInfoCacheFactory());
        CacheAdapterFactory<String, ParseInfo> cacheFactory = ((CacheAdapterFactory<String, ParseInfo>) factoryClass.newInstance());
        this.cachedPreparedStatementParams = cacheFactory.getInstance(this, this.myURL, getPreparedStatementCacheSize(),
                getPreparedStatementCacheSqlLimit(), this.props);
        //2.
        if (getUseServerPreparedStmts()) {
            this.serverSideStatementCheckCache = new LRUCache(cacheSize);
            this.serverSideStatementCache = new LRUCache(cacheSize) {
                private static final long serialVersionUID = 7692318650375988114L;
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Object, Object> eldest) {
                    if (this.maxElements <= 1) {
                        return false;
                    }
                    boolean removeIt = super.removeEldestEntry(eldest);
                    if (removeIt) {
                        ServerPreparedStatement ps = (ServerPreparedStatement) eldest.getValue();
                        ps.isCached = false;
                        ps.setClosed(false);

                        try {
                            ps.close();
                        } catch (SQLException sqlEx) {
                            // punt
                        }
                    }
                    return removeIt;
                }
            };
        }
    }
}
```

整个方法的逻辑明显可以分为2部分，第一部分的效果就是创建了一个cachedPreparedStatementParams并保存在ConnectionImpl内部，定义如下:

```java
/** A cache of SQL to parsed prepared statement parameters. */
private CacheAdapter<String, ParseInfo> cachedPreparedStatementParams;
```

factoryClass默认为PerConnectionLRUFactory:

![PerConnectionLRUFactory](images/PerConnectionLRUFactory.jpg)

为什么缓存接口名为CacheAdapter呢?

Mysql允许我们使用不同的CacheAdapterFactory实现，可以通过参数parseInfoCacheFactory传入，加入我们想用Guava cache代替默认的缓存实现，那么我们只需要编写一个类并实现CacheAdapter接口，内部委托给Guava cache，这不就相当于一个Adapter吗，猜的好有道理的样子，逃:)...

PerConnectionLRU内部完全委托给LRUCache实现:

![LRUCache](images/LRUCache.jpg)

:haha:.

### 服务端编译

默认Mysql的jdbc编译是在客户端完成的，我们可以通过参数useServerPrepStmts 将其改为在服务器端编译，不过Mysql官方建议应该非常谨慎(不要)修改这个参数，这两部分内容可以参考:

[What's the difference between cachePrepStmts and useServerPrepStmts in MySQL JDBC Driver](https://stackoverflow.com/questions/32286518/whats-the-difference-between-cacheprepstmts-and-useserverprepstmts-in-mysql-jdb)

### 存储过程缓存

jdbc标准里CallableStatement负责对存储过程的调用执行，而cacheCallableStmts参数正是用于开启对存储过程

