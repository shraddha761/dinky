/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.dinky.service.resource.impl;

import org.dinky.data.dto.TreeNodeDTO;
import org.dinky.data.enums.Status;
import org.dinky.data.exception.BusException;
import org.dinky.data.model.Resources;
import org.dinky.data.result.Result;
import org.dinky.mapper.ResourcesMapper;
import org.dinky.service.resource.BaseResourceManager;
import org.dinky.service.resource.ResourcesService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import cn.hutool.cache.impl.TimedCache;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.util.StrUtil;

@Service
public class ResourceServiceImpl extends ServiceImpl<ResourcesMapper, Resources> implements ResourcesService {
    private static final TimedCache<Integer, Resources> RESOURCES_CACHE = new TimedCache<>(30 * 1000);
    private static final long ALLOW_MAX_CAT_CONTENT_SIZE = 10 * 1024 * 1024;

    @Override
    public TreeNodeDTO createFolder(Integer pid, String fileName, String desc) {
        long count = count(
                new LambdaQueryWrapper<Resources>().eq(Resources::getPid, pid).eq(Resources::getFileName, fileName));
        if (count > 0) {
            throw new BusException("folder is exists!");
        }
        String path = "/" + fileName;
        Resources resources = new Resources();
        resources.setPid(pid);
        resources.setFileName(fileName);
        resources.setIsDirectory(true);
        resources.setType(0);
        resources.setFullName(pid < 1 ? path : getById(pid).getFullName() + path);
        resources.setSize(0L);
        resources.setDescription(desc);
        save(resources);
        return convertTree(resources);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rename(Integer id, String fileName, String desc) {
        Resources byId = getById(id);
        String sourceFullName = byId.getFullName();
        Assert.notNull(byId, () -> new BusException("resource is not exists!"));
        long count = count(new LambdaQueryWrapper<Resources>()
                .eq(Resources::getPid, byId.getPid())
                .eq(Resources::getFileName, fileName)
                .ne(Resources::getId, id));
        Assert.isFalse(count > 0, () -> new BusException("folder is exists!"));
        List<String> split = StrUtil.split(sourceFullName, "/");
        split.remove(split.size() - 1);
        split.add(fileName);
        String fullName = StrUtil.join("/", split);

        byId.setDescription(desc);
        byId.setFileName(fileName);
        byId.setFullName(fullName);
        updateById(byId);
        boolean isRunStorageMove = false;
        if (!byId.getIsDirectory()) {
            List<Resources> list = list(new LambdaQueryWrapper<Resources>().eq(Resources::getPid, byId.getId()));
            if (CollUtil.isNotEmpty(list)) {
                for (Resources resources : list) {
                    resources.setFullName(fullName + "/" + resources.getFileName());
                    isRunStorageMove = !resources.getIsDirectory() && !isRunStorageMove;
                }
                updateBatchById(list);
            }
        } else {
            isRunStorageMove = true;
            if (!isExistsChildren(id)) {
                return;
            }
        }
        if (isRunStorageMove) {
            getBaseResourceManager().rename(sourceFullName, fullName);
        }
    }

    @Override
    public List<TreeNodeDTO> showByTree(Integer pid, Integer showFloorNum) {
        if (pid < 0) {
            pid = -1;
        }
        showFloorNum = Opt.ofNullable(showFloorNum).orElse(2);
        List<TreeNodeDTO> data = new ArrayList<>();
        createTree(data, pid, showFloorNum, 0);
        return data;
    }

    @Override
    public void createTree(List<TreeNodeDTO> data, Integer pid, Integer showFloorNum, Integer currentFloor) {
        if (currentFloor > showFloorNum) {
            return;
        }
        List<Resources> list = list(new LambdaQueryWrapper<Resources>().eq(Resources::getPid, pid));
        for (Resources resources : list) {
            TreeNodeDTO tree = convertTree(resources);
            if (resources.getIsDirectory()) {
                List<TreeNodeDTO> children = new ArrayList<>();
                tree.setChildren(children);
                createTree(children, resources.getId(), showFloorNum, currentFloor + 1);
            }
            data.add(tree);
        }
    }

    private static TreeNodeDTO convertTree(Resources resources) {
        TreeNodeDTO treeNodeDTO = new TreeNodeDTO();
        treeNodeDTO.setId(resources.getId());
        treeNodeDTO.setName(resources.getFileName());
        treeNodeDTO.setSize(resources.getSize());
        treeNodeDTO.setParentId(resources.getPid());
        treeNodeDTO.setPath(resources.getFullName());
        treeNodeDTO.setLeaf(resources.getIsDirectory());
        treeNodeDTO.setDesc(resources.getDescription());
        return treeNodeDTO;
    }

    @Override
    public String getContentByResourceId(Integer id) {
        Resources resources = getById(id);
        Assert.notNull(resources, () -> new BusException(Status.RESOURCE_DIR_OR_FILE_NOT_EXIST));
        Assert.isFalse(resources.getSize() > ALLOW_MAX_CAT_CONTENT_SIZE, () -> new BusException("file is too large!"));
        return getBaseResourceManager().getFileContent(resources.getFullName());
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void uploadFile(Integer pid, String desc, MultipartFile file) {
        Resources pResource = RESOURCES_CACHE.get(pid, () -> getById(pid));
        if (!pResource.getIsDirectory()) {
            RESOURCES_CACHE.remove(pid);
            Integer realPid = pResource.getPid();
            pResource = RESOURCES_CACHE.get(pid, () -> getById(realPid));
        }
        long size = file.getSize();
        String fileName = file.getOriginalFilename();
        Resources currentUploadResource = getOne(
                new LambdaQueryWrapper<Resources>().eq(Resources::getPid, pid).eq(Resources::getFileName, fileName));
        String fullName;
        if (currentUploadResource != null) {
            if (desc != null) {
                currentUploadResource.setDescription(desc);
            }
            fullName = currentUploadResource.getFullName();
        } else {
            Resources resources = new Resources();
            resources.setPid(pid);
            resources.setFileName(fileName);
            resources.setIsDirectory(false);
            resources.setType(0);
            String prefixPath = pResource == null ? "" : pResource.getFullName();
            fullName = prefixPath + "/" + fileName;

            resources.setFullName(fullName);
            resources.setSize(size);
            resources.setDescription(desc);
            saveOrUpdate(resources);
        }
        getBaseResourceManager().putFile(fullName, file);

        List<Resources> resourceByPidToParent = getResourceByPidToParent(new ArrayList<>(), pid);
        resourceByPidToParent.forEach(x -> x.setSize(x.getSize() + size));
        updateBatchById(resourceByPidToParent);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean remove(Integer id) {
        Assert.isFalse(
                Opt.ofNullable(getById(id))
                                .orElseThrow(() -> new BusException(Status.RESOURCE_DIR_OR_FILE_NOT_EXIST))
                                .getPid()
                        == -1,
                () -> new BusException(Status.ROOT_DIR_NOT_ALLOW_DELETE));
        try {
            if (id < 1) {
                getBaseResourceManager().remove("/");
                // todo 删除主目录，实际是清空
                remove(new LambdaQueryWrapper<Resources>().ne(Resources::getId, 0));
            }
            Resources byId = getById(id);
            if (isExistsChildren(id)) {
                getBaseResourceManager().remove(byId.getFullName());
                if (byId.getIsDirectory()) {
                    List<Resources> resourceByPidToChildren =
                            getResourceByPidToChildren(new ArrayList<>(), byId.getId());
                    removeBatchByIds(resourceByPidToChildren);
                }
                List<Resources> resourceByPidToParent = getResourceByPidToParent(new ArrayList<>(), byId.getPid());
                resourceByPidToParent.forEach(x -> x.setSize(x.getSize() - byId.getSize()));
                updateBatchById(resourceByPidToParent);
                getBaseResourceManager().remove(byId.getFullName());
                return removeById(id);
            }
            return removeById(id);
        } catch (Exception e) {
            throw new BusException(Status.DELETE_FAILED);
        }
    }

    private boolean isExistsChildren(Integer id) {
        return count(new LambdaQueryWrapper<Resources>().eq(Resources::getPid, id)) > 0;
    }

    /**
     * 递归获取所有的资源，从pid到0
     *
     * @param resourcesList data
     * @param pid           pid
     * @return data
     */
    @Override
    public List<Resources> getResourceByPidToParent(List<Resources> resourcesList, Integer pid) {
        if (pid < 1) {
            return resourcesList;
        }
        Resources byId = RESOURCES_CACHE.get(pid, () -> getById(pid));
        resourcesList.add(byId);
        return getResourceByPidToParent(resourcesList, byId.getPid());
    }

    /**
     * 递归获取所有的资源，从id往下穿
     *
     * @param resourcesList data
     * @param pid           pid
     * @return data
     */
    @Override
    public List<Resources> getResourceByPidToChildren(List<Resources> resourcesList, Integer pid) {
        List<Resources> list = list(new LambdaQueryWrapper<Resources>().eq(Resources::getPid, pid));
        if (CollUtil.isEmpty(list)) {
            return resourcesList;
        }
        for (Resources resources : list) {
            resourcesList.add(resources);
            if (resources.getIsDirectory()) {
                getResourceByPidToChildren(resourcesList, resources.getId());
            }
        }
        return resourcesList;
    }

    /**
     * query Resources tree data
     *
     * @return {@link Result}< {@link List}< {@link Resources}>>}
     */
    @Override
    public List<Resources> getResourcesTree() {
        return buildResourcesTree(this.list());
    }

    /**
     * build resources tree
     *
     * @param resourcesList resources list
     * @return Resources tree
     */
    private List<Resources> buildResourcesTree(List<Resources> resourcesList) {
        // sort
        if (CollectionUtil.isNotEmpty(resourcesList)) {
            resourcesList = resourcesList.stream()
                    .sorted(Comparator.comparing(Resources::getId))
                    .collect(Collectors.toList());
        }

        List<Resources> returnList = new ArrayList<>();

        for (Resources resources : resourcesList) {
            //  get all child catalogue of parent catalogue id , the -1 is root catalogue
            if (resources.getPid() == -1) {
                recursionBuildResourcesAndChildren(resourcesList, resources);
                returnList.add(resources);
            }
        }
        if (returnList.isEmpty()) {
            returnList = resourcesList;
        }
        return returnList;
    }

    /**
     * recursion build resources and children
     *
     * @param resourcesList
     * @param resources
     */
    private void recursionBuildResourcesAndChildren(List<Resources> resourcesList, Resources resources) {
        // obtain a list of child nodes
        List<Resources> childList = getChildList(resourcesList, resources);
        resources.setChildren(childList);
        for (Resources tChild : childList) {
            if (hasChild(resourcesList, tChild)) {
                // Determine whether there are child nodes
                recursionBuildResourcesAndChildren(resourcesList, tChild);
            } else {
                tChild.setLeaf(true);
            }
        }
    }

    /**
     * get child list
     *
     * @param list
     * @param resources
     * @return
     */
    private List<Resources> getChildList(List<Resources> list, Resources resources) {
        List<Resources> childList = new ArrayList<>();
        for (Resources n : list) {
            if (n.getPid().longValue() == resources.getId().longValue()) {
                childList.add(n);
            }
        }
        return childList;
    }

    /**
     * Determine whether there are child nodes
     *
     * @param resourcesList
     * @param resources
     * @return
     */
    private boolean hasChild(List<Resources> resourcesList, Resources resources) {
        return getChildList(resourcesList, resources).size() > 0;
    }

    private BaseResourceManager getBaseResourceManager() {
        return BaseResourceManager.getInstance();
    }
}
