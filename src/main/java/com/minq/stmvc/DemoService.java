package com.minq.stmvc;

@GPService
public class DemoService implements IDemoService{
    public String get(String name) {
        return "My name is " + name;
    }
}
