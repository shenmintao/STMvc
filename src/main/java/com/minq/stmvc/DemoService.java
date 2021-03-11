package com.minq.stmvc;

@GPService
public class DemoService implements IDemoService{
    public String get(String abc) {
        return "My name is " + abc;
    }
}
