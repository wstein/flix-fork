package com.example;

import Acme.Greeter;
import java.util.ArrayList;

public class App {
    public static void main(String[] args) {
        String greeting = Greeter.greet("Java");
        System.out.println(greeting);
        System.out.println("length = " + Greeter.lengthOf(greeting));

        ArrayList<String> xs = new ArrayList<>();
        xs.add("a");
        xs.add("b");
        System.out.println("size = " + Greeter.sizeOf(xs));

        Greeter.announce();
    }
}
