package com.realtimetech.fermes.database;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import com.realtimetech.fermes.database.exception.FermesItemException;
import com.realtimetech.fermes.database.item.Item;
import com.realtimetech.fermes.database.lock.Lock;
import com.realtimetech.fermes.database.page.EmptyPagePointer;
import com.realtimetech.fermes.database.page.Page;
import com.realtimetech.fermes.database.page.Page.PageSerializer;
import com.realtimetech.fermes.database.page.exception.BlockIOException;
import com.realtimetech.fermes.database.page.exception.PageIOException;
import com.realtimetech.fermes.database.page.file.impl.MemoryFileWriter;
import com.realtimetech.fermes.database.root.RootItem;
import com.realtimetech.fermes.database.root.RootItem.ItemCreator;
import com.realtimetech.fermes.database.zip.ZipUtils;
import com.realtimetech.fermes.exception.FermesDatabaseException;
import com.realtimetech.kson.builder.KsonBuilder;
import com.realtimetech.kson.element.JsonObject;
import com.realtimetech.kson.exception.DeserializeException;
import com.realtimetech.kson.exception.SerializeException;
import com.realtimetech.kson.util.pool.KsonPool;

public class Database {
	private Charset charset;

	private ArrayList<Page> pages;

	private File databaseDirectory;

	private int pageId;

	private int pageSize;
	private int blockSize;

	private long maxMemory;
	private long currentMemory;

	private Link<? extends Item> headObject;
	private Link<? extends Item> tailObject;

	private Queue<EmptyPagePointer> emptyPagePointers;

	private KsonPool ksonPool;

	private PageSerializer pageSerializer;

	private Link<RootItem> rootItem;

	private Lock diskLock;
	private Lock processLock;

	private List<Link<? extends Item>> frozeLinks;

	public Database(File databaseDirectory) throws FermesDatabaseException {
		this();

		this.databaseDirectory = databaseDirectory;

		load();

		if (this.blockSize == -1 || this.maxMemory == -1) {
			throw new FermesDatabaseException("Can't load database, not exist database.");
		}
	}

	public Database(File databaseDirectory, int pageSize, int blockSize, long maxMemory) throws FermesDatabaseException {
		this();

		this.databaseDirectory = databaseDirectory;

		if (databaseDirectory.isDirectory() && databaseDirectory.exists()) {
			File configFile = new File(databaseDirectory, "database.config");

			if (configFile.exists()) {
				throw new FermesDatabaseException("Can't create database with parameters.");
			}
		}

		this.pageSize = pageSize;
		this.blockSize = blockSize;
		this.maxMemory = maxMemory;

		load();
	}

	Database() {
		this.diskLock = new Lock();
		this.processLock = new Lock();

		this.charset = Charset.forName("UTF-8");

		this.frozeLinks = new LinkedList<Link<? extends Item>>();
		this.pages = new ArrayList<Page>();
		this.emptyPagePointers = new LinkedList<EmptyPagePointer>();
		this.pageSerializer = new PageSerializer(this);

		this.pageId = 0;
		this.currentMemory = 0;

		KsonBuilder ksonBuilder = new KsonBuilder();
		ksonBuilder.registerTransformer(Link.class, new LinkTransformer(this));
		this.ksonPool = new KsonPool(ksonBuilder);

		this.pageSize = 0;
		this.blockSize = -1;
		this.maxMemory = -1;
	}

	/**
	 * Getting root links
	 */

	public <T extends Item> Link<T> getLink(String name, ItemCreator<T> creator) throws PageIOException, FermesItemException {
		return this.rootItem.get().getLink(name, creator);
	}

	/**
	 * Save and load methods
	 */

	@SuppressWarnings("unchecked")
	private void load() throws FermesDatabaseException {
		try {
			this.processLock.waitLock();
			this.diskLock.lock();

			if (!databaseDirectory.isDirectory() || !databaseDirectory.exists()) {
				this.databaseDirectory.mkdirs();
			}

			File configFile = new File(databaseDirectory, "database.config");

			if (!configFile.exists()) {
				try {
					configFile.createNewFile();
				} catch (IOException e1) {
					throw new FermesDatabaseException("Can't create database, access denied when create config file.");
				}

				JsonObject jsonObject = new JsonObject();

				jsonObject.put("pageId", 0);
				jsonObject.put("pageSize", this.pageSize);
				jsonObject.put("blockSize", this.blockSize);
				jsonObject.put("maxMemory", this.maxMemory);

				try {
					Files.writeString(configFile.toPath(), jsonObject.toKsonString());
				} catch (IOException e) {
					throw new FermesDatabaseException("Can't create database, access denied when create config file.");
				}

				try {
					this.rootItem = this.createLink(null, new RootItem());
				} catch (PageIOException e) {
					throw new FermesDatabaseException("Can't create database, failure creation root link(0).");
				}
			}

			JsonObject jsonObject;

			try {
				jsonObject = (JsonObject) ksonPool.get().fromString(Files.readString(configFile.toPath()));
			} catch (IOException e) {
				throw new FermesDatabaseException("Can't load database, IOException in parse json config.");
			}

			this.pageSize = (int) jsonObject.get("pageSize");
			this.blockSize = (int) jsonObject.get("blockSize");
			this.maxMemory = (long) jsonObject.get("maxMemory");

			for (int index = 0; index < (int) jsonObject.get("pageId"); index++) {
				try {
					Page page = this.createPageWithoutEmptyPointer();
					this.pages.add(page);

					MemoryFileWriter pageBuffer = new MemoryFileWriter(page.getPageFile());
					pageBuffer.load();

					pageSerializer.read(page, pageBuffer);
				} catch (IOException e) {
					e.printStackTrace();
					throw new FermesDatabaseException("Can't load database, IOException in parse page files.");
				}
			}
			System.gc();

			this.rootItem = (Link<RootItem>) this.getLinkByGid(0);

			if (this.rootItem == null) {
				throw new FermesDatabaseException("Can't create database, failure load root link(0).");
			}
		} finally {
			this.diskLock.unlock();
		}
	}

	public void save() throws FermesDatabaseException {
		try {
			this.processLock.waitLock();
			this.diskLock.lock();

			JsonObject jsonObject = new JsonObject();

			jsonObject.put("pageId", this.pageId);
			jsonObject.put("pageSize", this.pageSize);
			jsonObject.put("blockSize", this.blockSize);
			jsonObject.put("maxMemory", this.maxMemory);

			try {
				Files.writeString(new File(databaseDirectory, "database.config").toPath(), jsonObject.toKsonString());
			} catch (IOException e) {
				throw new FermesDatabaseException("Can't save database, access denied when save config file.");
			}

			for (Page page : this.pages) {
				page.enableBlocksDirectly();

				for (Link<? extends Item> link : page.getLinks()) {
					if (link != null) {
						try {
							writeLinkBlocks(link);
						} catch (BlockIOException | FermesItemException e) {
							e.printStackTrace();

							throw new FermesDatabaseException("Can't save database, BlockIOException in save links.");
						}
					}
				}

				try {
					MemoryFileWriter pageBuffer = new MemoryFileWriter(pageSerializer.getWriteLength(page), page.getPageFile());
					pageSerializer.write(page, pageBuffer);
					pageBuffer.save();
				} catch (IOException e) {
					e.printStackTrace();
					throw new FermesDatabaseException("Can't save database, IOException in save page files.");
				}

				try {
					page.disableBlocksDirectly();
				} catch (IOException e) {
					throw new FermesDatabaseException("Can't save database, IOException in save buffer.");
				}
			}
		} finally {
			this.diskLock.unlock();
		}
	}

	public void saveAndBackup(File backupFile) throws FermesDatabaseException, IOException {
		try {
			this.processLock.waitLock();
			this.diskLock.lock();

			save();

			ZipUtils.zipFolder(databaseDirectory, backupFile);
		} finally {
			this.diskLock.unlock();
		}

	}

	public void close() throws FermesDatabaseException {
		try {
			this.processLock.waitLock();
			this.diskLock.lock();

			save();

			for (Page page : this.pages) {
				int index = 0;
				for (Link<? extends Item> link : page.getLinks()) {
					if (link != null) {
						try {
							unloadLink(link, true);
						} catch (BlockIOException | FermesItemException e) {
							e.printStackTrace();

							throw new FermesDatabaseException("Can't unload database, BlockIOException in save links.");
						}
						page.getLinks()[index] = null;
					}

					index++;
				}
			}

			this.pages.clear();

			System.gc();
		} finally {
			this.diskLock.unlock();
		}
	}

	/**
	 * Getter for options
	 */

	public File getDatabaseDirectory() {
		return databaseDirectory;
	}

	public int getPageSize() {
		return pageSize;
	}

	public long getMaxMemory() {
		return maxMemory;
	}

	public long getCurrentMemory() {
		return currentMemory;
	}

	/**
	 * Access method using gid methods
	 */

	public Link<? extends Item> getLinkByGid(long gid) {
		this.diskLock.waitLock();

		if (gid == -1)
			return null;

		Page pageByGid = this.getPageByGid(gid);

		if (pageByGid == null)
			return null;

		return pageByGid.getLinkByIndex((int) (gid % this.pageSize));
	}

	public Page getPageByGid(long gid) {
		this.diskLock.waitLock();

		int index = (int) (gid / this.pageSize);
		return this.pages.size() > index ? this.pages.get(index) : null;
	}

	/**
	 * Serialize / deserialize item methods
	 */

	private byte[] serializeItem(Item item) throws FermesItemException {
		try {
			JsonObject jsonObject = new JsonObject();
			jsonObject.put("class", item.getClass().getName());
			jsonObject.put("item", ksonPool.get().fromObject(item));

			return ksonPool.writer().toString(jsonObject).getBytes(charset);
		} catch (SerializeException e) {
			throw new FermesItemException("Can't serialize object (item to bytes).");
		}
	}

	private Item deserializeItem(byte[] bytes) throws FermesItemException {
		try {
			JsonObject jsonObject = (JsonObject) ksonPool.get().fromString(new String(bytes, charset));

			Object object = ksonPool.get().toObject(Database.class.getClassLoader().loadClass((String) jsonObject.get("class")), jsonObject.get("item"));

			return (Item) object;
		} catch (IOException | ClassNotFoundException | DeserializeException e) {
			throw new FermesItemException("Can't serialize object (item to bytes).");
		}
	}

	synchronized void fitMemory(long size) throws BlockIOException, FermesItemException {
		this.frozeLinks.clear();
		while (this.currentMemory + size > this.maxMemory) {
			Link<? extends Item> object = this.tailObject;

			if (object == null) {
				break;
			}

			if (!object.accessed && !object.froze) {
				this.unloadLink(object, false);
			} else {
				object.accessed = false;
				remove(object);

				if (object.froze) {
					this.frozeLinks.add(object);
				} else {
					join(object);
				}
			}
		}

		for (Link<? extends Item> object : this.frozeLinks) {
			join(object);
		}
	}

	/**
	 * Load, unload, update link methods
	 */

	@SuppressWarnings("unchecked")
	protected <R extends Item> void loadLink(Link<R> link) throws BlockIOException, FermesItemException {
		synchronized (link) {
			if (!link.isLoaded()) {
				try {
					this.processLock.tryLock();
					this.diskLock.waitLock();

					byte[] bytes = link.getPage().readBlocks(link.blockIds, link.itemLength);

					this.fitMemory(bytes.length);

					link.itemLength = bytes.length;
					link.item = (R) deserializeItem(bytes);

					link.item.onLoad(link);

					synchronized (this) {
						join(link);
						this.currentMemory += link.itemLength;
					}
				} finally {
					this.processLock.unlock();
				}
			}
		}
	}

	private void unloadLink(Link<? extends Item> link, boolean justMemory) throws BlockIOException, FermesItemException {
		synchronized (link) {
			if (link.isLoaded()) {
				try {
					this.processLock.tryLock();
					this.diskLock.waitLock();

					if (!justMemory) {
						writeLinkBlocks(link);
					}
					link.item = null;

					synchronized (this) {
						remove(link);
						this.currentMemory -= link.itemLength;
					}
				} finally {
					this.processLock.unlock();
				}
			}
		}
	}

	private void writeLinkBlocks(Link<? extends Item> link) throws FermesItemException, BlockIOException {
		if (link.isLoaded()) {
			byte[] bytes = serializeItem(link.item);

			link.itemLength = bytes.length;
			link.blockIds = link.getPage().fitBlockIds(link.blockIds, link.itemLength);
			link.getPage().writeBlocks(link.blockIds, bytes);
		}
	}

	private <R extends Item> void updateLinkLength(Link<R> link) throws BlockIOException, FermesItemException {
		try {
			this.processLock.tryLock();
			this.diskLock.waitLock();

			byte[] bytes = serializeItem(link.item);

			this.fitMemory(bytes.length);

			link.itemLength = bytes.length;

			synchronized (this) {
				join(link);
				this.currentMemory += link.itemLength;
			}
		} finally {
			this.processLock.unlock();
		}
	}

	/**
	 * Create or remove link methods
	 */

	protected <R extends Item> Link<R> createLink(Link<? extends Item> parentLink, R item) throws PageIOException {
		try {
			this.processLock.tryLock();
			this.diskLock.waitLock();

			Page page = null;
			int nextIndex = 0;

			synchronized (this.emptyPagePointers) {
				if (this.emptyPagePointers.isEmpty()) {
					try {
						this.pages.add(createPage());
					} catch (IOException e) {
						throw new PageIOException("Can't create new page.");
					}
				}

				EmptyPagePointer pointer = this.emptyPagePointers.peek();

				page = pointer.getTargetPage();
				nextIndex = pointer.nextIndex();

				if (pointer.isDone()) {
					this.emptyPagePointers.poll();
				}
			}

			long gid = page.getId() * this.getPageSize() + nextIndex;
			Link<R> link = new Link<R>(this, page, parentLink == null ? -1 : parentLink.gid, gid);
			link.item = item;

			item.onCreate((Link<R>) link);
			item.onLoad(link);

			page.setLinkByIndex(nextIndex, link);

			if (parentLink != null) {
				parentLink.createChildLinksIfNotExist();

				synchronized (parentLink.childLinks) {
					parentLink.childLinks.add(gid);
				}
			}

			try {
				this.updateLinkLength(link);
			} catch (BlockIOException | FermesItemException e) {
				throw new PageIOException("Can't update link actuall length.");
			}

			return link;
		} finally {
			this.processLock.unlock();
		}
	}

	protected boolean removeLink(Link<? extends Item> link) {
		try {
			this.processLock.tryLock();
			this.diskLock.waitLock();

			if (!link.removed) {
				link.removed = true;

				if (link.isLoaded()) {
					try {
						this.unloadLink(link, true);
					} catch (BlockIOException | FermesItemException e) {
						e.printStackTrace();

						return false;
					}
				}

				Link<? extends Item> parentLink = this.getLinkByGid(link.parentLink);

				if (parentLink != null && parentLink.childLinks != null) {

					synchronized (parentLink.childLinks) {
						parentLink.childLinks.remove(link.gid);
					}
				}

				if (link.childLinks != null) {
					synchronized (link.childLinks) {
						for (long childLinkGid : link.childLinks) {
							Link<? extends Item> childLink = this.getLinkByGid(childLinkGid);

							if (childLink != null) {
								this.removeLink(childLink);
							}
						}
					}
				}

				long gid = link.getGid();
				int index = (int) (gid % this.pageSize);

				Page page = this.pages.get((int) (gid / this.pageSize));

				page.removeLinkByIndex(link.blockIds, index);

				this.addEmptyPagePointer(new EmptyPagePointer(page, index));

				return true;
			}

			return false;
		} finally {
			this.processLock.unlock();
		}
	}

	/**
	 * Empty pointer methods
	 */

	public void addEmptyPagePointer(EmptyPagePointer emptyPagePointer) {
		synchronized (this.emptyPagePointers) {
			this.emptyPagePointers.add(emptyPagePointer);
		}
	}

	/**
	 * Page control methods
	 */

	public Page createPage() throws IOException {
		synchronized (this.emptyPagePointers) {
			Page page = createPageWithoutEmptyPointer();

			this.addEmptyPagePointer(new EmptyPagePointer(page, 0, this.pageSize - 1));

			return page;
		}
	}

	public Page createPageWithoutEmptyPointer() throws IOException {
		synchronized (this.pages) {
			Page page = new Page(this, this.pageId++, this.pageSize, this.blockSize);

			return page;
		}
	}

	/**
	 * Used tree methods
	 */

	synchronized void join(Link<? extends Item> object) {
		if (this.headObject == null) {
			object.prevObject = null;
			object.nextObject = null;

			this.tailObject = object;
			this.headObject = object;
		} else {
			object.prevObject = null;
			object.nextObject = this.headObject;
			this.headObject.prevObject = object;
			this.headObject = object;
		}
	}

	synchronized void remove(Link<? extends Item> object) {
		if (object.nextObject != null) {
			object.nextObject.prevObject = object.prevObject;
		}
		if (object.prevObject != null) {
			object.prevObject.nextObject = object.nextObject;
		}

		if (this.tailObject == object) {
			this.tailObject = object.prevObject;
		}

		if (this.headObject == object) {
			this.headObject = object.nextObject;
		}

		object.nextObject = null;
		object.prevObject = null;
	}
}
