/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import { Injectable } from '@angular/core';
import {ArchivaRequestService} from "@app/services/archiva-request.service";
import {RoleTemplate} from "@app/model/role-template";
import { Observable } from 'rxjs';
import { Role } from '@app/model/role';
import {HttpResponse} from "@angular/common/http";
import {PagedResult} from "@app/model/paged-result";
import {UserInfo} from "@app/model/user-info";
import {RoleUpdate} from "@app/model/role-update";
import { User } from '@app/model/user';

@Injectable({
  providedIn: 'root'
})
export class RoleService {

  constructor(private rest: ArchivaRequestService) { }

  public getTemplates() : Observable<RoleTemplate[]> {
    return this.rest.executeRestCall("get", "redback", "roles/templates", null);
  }

  public assignRole(roleId, userId) : Observable<HttpResponse<Role>> {
    return this.rest.executeResponseCall<Role>("put", "redback", "roles/" + roleId + "/user/" + userId, null);
  }

  public unAssignRole(roleId, userId) : Observable<HttpResponse<Role>> {
    return this.rest.executeResponseCall<Role>("delete", "redback", "roles/" + roleId + "/user/" + userId, null);
  }

  public query(searchTerm: string, offset: number = 0, limit: number = 10, orderBy: string[] = ['id'], order: string = 'asc'): Observable<PagedResult<Role>> {
    if (searchTerm == null) {
      searchTerm = ""
    }
    if (orderBy == null || orderBy.length == 0) {
      orderBy = ['id'];
    }
    return this.rest.executeRestCall<PagedResult<Role>>("get", "redback", "roles", {
      'q': searchTerm,
      'offset': offset,
      'limit': limit,
      'orderBy': orderBy,
      'order': order
    });
  }

  public queryAssignedUsers(roleId: string,
                            searchTerm: string, offset: number = 0, limit: number = 5,
                            orderBy: string[] = ['id'], order: string = 'asc'): Observable<PagedResult<User>> {
    if (searchTerm == null) {
      searchTerm = ""
    }
    if (orderBy == null || orderBy.length == 0) {
      orderBy = ['id'];
    }
    return this.rest.executeRestCall<PagedResult<User>>("get", "redback", "roles/" + roleId + "/user", {
      'q': searchTerm,
      'offset': offset,
      'limit': limit,
      'orderBy': orderBy,
      'order': order
    });
  }

  /**
   * Query for assigned users, that are part of the parent roles.
   *
   * @param roleId
   * @param searchTerm
   * @param offset
   * @param limit
   * @param orderBy
   * @param order
   * @param parentsOnly
   */
  public queryAssignedParentUsers(roleId: string,
                            searchTerm: string, offset: number = 0, limit: number = 5,
                            orderBy: string[] = ['id'], order: string = 'asc', parentsOnly:boolean=true): Observable<PagedResult<User>> {
    if (searchTerm == null) {
      searchTerm = ""
    }
    if (orderBy == null || orderBy.length == 0) {
      orderBy = ['id'];
    }
    const recurseFlag = parentsOnly ? 'parentsOnly' : 'true';
    return this.rest.executeRestCall<PagedResult<User>>("get", "redback", "roles/" + roleId + "/user", {
      'recurse':recurseFlag,
      'q': searchTerm,
      'offset': offset,
      'limit': limit,
      'orderBy': orderBy,
      'order': order
    });
  }

  public queryUnAssignedUsers(roleId: string,
                            searchTerm: string, offset: number = 0, limit: number = 5,
                            orderBy: string[] = ['id'], order: string = 'asc'): Observable<PagedResult<User>> {
    if (searchTerm == null) {
      searchTerm = ""
    }
    if (orderBy == null || orderBy.length == 0) {
      orderBy = ['id'];
    }
    return this.rest.executeRestCall<PagedResult<User>>("get", "redback", "roles/" + roleId + "/unassigned", {
      'q': searchTerm,
      'offset': offset,
      'limit': limit,
      'orderBy': orderBy,
      'order': order
    });
  }


  public getRole(roleId:string) : Observable<Role> {
    return this.rest.executeRestCall("get", "redback", "roles/" + roleId, null);
  }

  public updateRole(role:RoleUpdate) : Observable<Role> {
    return this.rest.executeRestCall("patch", "redback", "roles/" + role.id, role);
  }

}
