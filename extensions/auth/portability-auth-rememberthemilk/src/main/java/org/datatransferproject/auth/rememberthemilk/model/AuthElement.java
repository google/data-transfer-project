/*
 * Copyright 2018 The Data Transfer Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.datatransferproject.auth.rememberthemilk.model;

import com.google.api.client.util.Key;

/**
 * Response from rtm.auth.getToken
 *
 * <p>Example: <?xml version='1.0' encoding='UTF-8'?><rsp
 * stat="ok"><auth><token>footoken</token><perms>write</perms><user
 * id="123456" username="username" fullname="User Name"/></auth></rsp>
 */
public class AuthElement extends RememberTheMilkResponse {

  @Key("auth")
  public Auth auth;

  @Override
  public String toString() {
    return "Auth(stat=" + stat + ", auth=" + auth + ")";
  }

  public static class Auth {

    @Key("token")
    public String token;

    @Key("perms")
    public String perms;

    @Key("user")
    public User user;

    @Override
    public String toString() {
      return "Auth(token=" + token + ", perms=" + perms + ", user=" + user + ")";
    }
  }


}
