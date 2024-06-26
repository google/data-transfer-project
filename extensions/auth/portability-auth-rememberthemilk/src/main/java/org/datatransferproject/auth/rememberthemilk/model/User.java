package org.datatransferproject.auth.rememberthemilk.model;



import com.google.api.client.util.Key;

    public class User {
        @Key("@id")
        public int id;

        @Key("@username")
        public String username;

        @Key("@fullname")
        public String fullname;

        @Override
        public String toString() {
            return "User(id=" + id + ", username=" + username + ", fullname=" + fullname +")";
        }
    }


