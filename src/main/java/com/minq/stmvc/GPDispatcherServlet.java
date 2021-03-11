package com.minq.stmvc;

import jdk.nashorn.internal.objects.annotations.Property;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.rmi.ServerException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GPDispatcherServlet extends HttpServlet {
    private static final long serialVersionUID = 696036519381983879L;

    //保存application.properties配置文件中的内容
    private Properties contextConfig = new Properties();

    //保存扫描的所有的类名
    private List<String> classNames = new ArrayList<>();

    //传说中的IOC容器，我们来揭开他的神秘面纱
    //主要是为了简化程序，暂时不考虑ConcurrentHashMap
    //主要还是关注设计思想和原理
    private Map<String, Object> ioc = new HashMap<>();

    //保存url和Method的对应关系
    private List<Handler> handlerMapping = new ArrayList<>();

    //加载配置文件
    private void doLoadConfig(String contextConfigLocation) {
        //直接通过类路径找到Spring主配置文件所在的路径
        //并且将其读取出来放到Properties对象中
        //相当于将scanPackage=com.minq.*保存到了内存中
        InputStream fis = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);
        try {
            contextConfig.load(fis);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if(null != fis) {
                try {
                    fis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void doScanner(String scanPackage) {
        //scanPackage = com.minq.*，存储肚饿是包路径
        //转换为文件路径，实际上就是把.替换为/
        URL url = this.getClass().getClassLoader().getResource("/" +
                scanPackage.replaceAll("\\.", "/"));

        File classPath = new File(url.getFile());
        for (File file:classPath.listFiles()) {
            if(file.isDirectory()) {
                doScanner(scanPackage + "." + file.getName());
            } else {
                if(!file.getName().endsWith(".class")) {
                    continue;
                }
                String className = (scanPackage + "." + file.getName().replace(".class", ""));
                classNames.add(className);
            }
        }
    }

    private void doInstance() {
        //初始化，为DI做准备

        if(classNames.isEmpty()) {
            return;
        }

        try {
            for (String className: classNames) {
                Class<?> clazz = Class.forName(className);

                //什么样的类才需要初始化
                //加了注解的类才初始化，怎么判断？
                //为了简化代码，主要体会设计思想，只用@Controller和@Service举例
                //@Componment等就不举例了
                if(clazz.isAnnotationPresent(GPController.class)) {
                    Object instance = clazz.newInstance();

                    //Spring默认类名首字母小写
                    String beanName = toLowerFirstCase(clazz.getSimpleName());
                    ioc.put(beanName, instance);
                } else if(clazz.isAnnotationPresent(GPService.class)) {

                    //1. 自定义的beanName
                    GPService service = clazz.getAnnotation(GPService.class);
                    String beanName = service.value();
                    //2. 默认类名首字母小写
                    if("".equals(beanName.trim())) {
                        beanName = toLowerFirstCase(clazz.getSimpleName());
                    }
                    Object instance = clazz.newInstance();
                    ioc.put(beanName, instance);

                    //3. 根据类型自动赋值，这是投机取巧的方式
                    for(Class<?> i: clazz.getInterfaces()) {
                        if(ioc.containsKey(i.getName())) {
                            throw new Exception("The " + i.getName() + "is exists!!");
                        }

                        //把接口的类型直接当成key
                        ioc.put(i.getName(), instance);
                    }
                } else {
                    continue;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void doAutowired() {
        if(ioc.isEmpty()) {
            return;
        }

        for(Map.Entry<String, Object> entry:ioc.entrySet()) {
            //获取所有的字段，包括private、protected、default类型
            //正常来说，普通的OOP编程只能获得public类型的字段
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            for(Field field: fields) {
                if(!field.isAnnotationPresent(GPAutowired.class)) {
                    continue;
                }
                GPAutowired autowired = field.getAnnotation(GPAutowired.class);

                //如果用户没有自定义beanName，默认就根据类型注入
                //这个地方省去了对类名首字母小写的情况的判断，这个作为课后作业自己实现
                String beanName = autowired.value().trim();
                if("".equals(beanName)) {
                    //获得接口的类型，作为key，稍后用这个key到IOC容器中取值
                    beanName = field.getType().getName();
                }

                //如果是public以外的类型，只要加了@Autowired注解都要强制复制
                field.setAccessible(true);

                try {
                    //用反射机制动态给字段赋值
                    field.set(entry.getValue(), ioc.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void initHandlerMapping() {
        //初始化url和method的一对一关系
        if(ioc.isEmpty()) {
            return;
        }

        for(Map.Entry<String, Object> entry: ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();

            if(!clazz.isAnnotationPresent(GPController.class)) {
                continue;
            }

            //保存写在类上面的@GPRequestMapping
            String baseUrl = "";
            if(clazz.isAnnotationPresent(GPRequestMapping.class)) {
                GPRequestMapping requestMapping = clazz.getAnnotation(GPRequestMapping.class);
                baseUrl = requestMapping.value();
            }

            //默认获取所有的public类型的方法
            for(Method method: clazz.getMethods()) {
                if(!method.isAnnotationPresent(GPRequestMapping.class)) {
                    continue;
                }
                GPRequestMapping requestMapping = method.getAnnotation(GPRequestMapping.class);

                String regex = ("/" + baseUrl + "/" + requestMapping.value()).replaceAll("/+","/");
                Pattern pattern = Pattern.compile(regex);
                handlerMapping.add(new Handler(pattern, entry.getValue(), method));
                System.out.println("Mapped:" + regex + "," + method);
            }
        }
    }
    private String toLowerFirstCase(String simpleName) {
        char[] chars = simpleName.toCharArray();
        chars[0] +=32;
        return String.valueOf(chars);
    }


    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServerException, IOException {
        this.doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServerException, IOException {
        try {
            doDispatch(req, resp);
        } catch (Exception e) {
            e.printStackTrace();
            resp.getWriter().write("500 Exception" + Arrays.toString(e.getStackTrace()));
        }
        return;
    }

    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws Exception {
//        String url = req.getRequestURI();
//        String contextPath = req.getContextPath();
//        url = url.replace(contextPath, "").replaceAll("/+", "/");
//        if (!this.handlerMapping.containsKey(url)) {
//            resp.getWriter().write("404 Not Found!!");
//            return;
//        }

        Handler handler = getHandler(req);
        if(handler == null) {
            resp.getWriter().write("404 Not Found!!");
            return;
        }

//        Method method = (Method) this.handlerMapping.get(url);

        //第一个参数：方法所在的实例
        //第二个参数：调用时所需要的实参
        Map<String, String[]> params = req.getParameterMap();
        //method.invoke(this.mapping.get(method.getDeclaringClass().getName()), req, resp, params.get("name")[0]);


        //获取方法的形参列表
        Class<?>[] parameterTypes = handler.method.getParameterTypes();

        //保存请求的url参数列表
        Map<String, String[]> parameterMap = req.getParameterMap();

        //保存赋值参数的位置
        Object[] paramValues = new Object[parameterTypes.length];

        //根据参数位置动态赋值
//        for(int i = 0; i < parameterTypes.length; i++){
//            Class parameterType = parameterTypes[i];
//            if(parameterType == HttpServletRequest.class) {
//                paramValues[i] = req;
//            } else if(parameterType == HttpServletResponse.class) {
//                paramValues[i] = resp;
//            } else if(parameterType == String.class) {
//                //提取方法中加了注解的参数
//                Annotation[][] pa = method.getParameterAnnotations();
//                for(int j=0; j< pa.length; j++) {
//                    for(Annotation a: pa[i]) {
//                        if(a instanceof GPRequestParam) {
//                            String paramName = ((GPRequestParam) a).value();
//                            if(!"".equals(paramName.trim())) {
//                                String value = Arrays.toString(parameterMap.get(paramName)).
//                                        replaceAll("\\[|\\]", "").
//                                        replaceAll("\\s", ",");
//                                paramValues[i] = value;
//                            }
//                        }
//                    }
//                }
//            }
//        }


        for(Map.Entry<String, String[]> param: params.entrySet()) {
            String value = Arrays.toString(param.getValue()).replaceAll("\\[|\\]", "").
                    replaceAll("\\s", ",");

            if(!handler.paramIndexMapping.containsKey(param.getKey())) {
                continue;
            }

            int index = handler.paramIndexMapping.get(param.getKey());
            paramValues[index] = convert(parameterTypes[index], value);
        }

        if(handler.paramIndexMapping.containsKey(HttpServletRequest.class.getName())) {
            int reqIndex = handler.paramIndexMapping.get(HttpServletRequest.class.getName());
            paramValues[reqIndex] = req;
        }

        if(handler.paramIndexMapping.containsKey(HttpServletResponse.class.getName())) {
            int respIndex = handler.paramIndexMapping.get(HttpServletResponse.class.getName());
            paramValues[respIndex] = resp;
        }

        Object returnValue = handler.method.invoke(handler.controller, paramValues);
        if(returnValue == null || returnValue instanceof Void) {
            return;
        }
        resp.getWriter().write(returnValue.toString());

        //投机取巧的方式
        //通过发射获得Method所在的Class，获得Class之后还要获得Class的名称
        //再调用toLowerFirstCase获得beanName
//        String beanName = toLowerFirstCase(method.getDeclaringClass().getSimpleName());
//        method.invoke(ioc.get(beanName), paramValues);
    }

    private Handler getHandler(HttpServletRequest req) throws  Exception {
        if (handlerMapping.isEmpty()) {
            return null;
        }
        String url = req.getRequestURI();
        String contextPath = req.getContextPath();
        url = url.replace(contextPath, "").replaceAll("/+", "/");
        for(Handler handler: handlerMapping) {
            try {
                Matcher matcher = handler.pattern.matcher(url);
                if(!matcher.matches()) {
                    continue;
                }
                return handler;
                //如果没有匹配上就继续匹配下一个
            } catch (Exception e) {
                throw e;
            }
        }
        return null;
    }

    //url传过来的参数都是String类型的，由于HTTP基于字符串协议
    //只需要把String转换为任意类型
    private Object convert(Class<?> type, String value) {
        if(Integer.class == type) {
            return Integer.valueOf(value);
        }
        return value;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        InputStream is = null;
        try {
            //1. 加载配置文件
            doLoadConfig(config.getInitParameter("contextConfigLocation"));
            //2. 扫描相关的类
            doScanner(contextConfig.getProperty("scanPackage"));

            //3. 初始化扫描到的类，并且将他们放入IOC容器中
            doInstance();

            //4. 完成依赖注入
            doAutowired();

            //5. 初始化HandlerMapping
            initHandlerMapping();

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if(is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        System.out.println("STMvc Framework is init");
    }
 }
