package apache;

import java.util.*;

public class forloop {
    public static void main(String[] args) {
        ArrayList<Map<String,Object>> listed = new ArrayList<Map<String, Object>>();
        Map<String,Object> dataMap = new HashMap<>();

        for(int i =0; i<10; i++){
            Map<String,Object> subMap  = new HashMap<>();
            subMap.put("1","2");
            subMap.put("2","3");
            listed.add(subMap);
        }
        dataMap.put("resulted",listed);
    }

}
