package com.accountingstar.repositories

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    companion object {
        const val DATABASE_NAME = "accounting_star.db"
        const val DATABASE_VERSION = 1
    }

    override fun onCreate(db: SQLiteDatabase) {
        // جدول شجرة الحسابات
        db.execSQL("""
            CREATE TABLE accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                code TEXT NOT NULL,
                name TEXT NOT NULL,
                parent_id INTEGER,
                type TEXT,
                FOREIGN KEY(parent_id) REFERENCES accounts(id)
            );
        """)
        // جدول المستودعات (رئيسية وفرعية)
        db.execSQL("""
            CREATE TABLE warehouses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                parent_id INTEGER,
                location TEXT,
                FOREIGN KEY(parent_id) REFERENCES warehouses(id)
            );
        """)
        // جدول الأصناف
        db.execSQL("""
            CREATE TABLE products (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                code TEXT NOT NULL,
                name TEXT NOT NULL,
                category_id INTEGER,
                subcategory_id INTEGER,
                unit TEXT,
                purchase_price REAL,
                sale_price REAL,
                FOREIGN KEY(category_id) REFERENCES categories(id),
                FOREIGN KEY(subcategory_id) REFERENCES subcategories(id)
            );
        """)
        // جدول العملاء
        db.execSQL("""
            CREATE TABLE customers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                code TEXT NOT NULL,
                name TEXT NOT NULL,
                address TEXT,
                phone TEXT,
                email TEXT,
                tax_number TEXT
            );
        """)
        // جدول الموردين
        db.execSQL("""
            CREATE TABLE suppliers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                code TEXT NOT NULL,
                name TEXT NOT NULL,
                address TEXT,
                phone TEXT,
                email TEXT,
                tax_number TEXT
            );
        """)
        // جدول الفواتير (شراء/بيع)
        db.execSQL("""
            CREATE TABLE invoices (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL, -- PURCHASE أو SALE
                date TEXT NOT NULL,
                customer_id INTEGER,
                supplier_id INTEGER,
                total REAL,
                tax REAL,
                discount REAL,
                FOREIGN KEY(customer_id) REFERENCES customers(id),
                FOREIGN KEY(supplier_id) REFERENCES suppliers(id)
            );
        """)
        // تفاصيل الفاتورة
        db.execSQL("""
            CREATE TABLE invoice_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                invoice_id INTEGER NOT NULL,
                product_id INTEGER NOT NULL,
                quantity REAL NOT NULL,
                unit_price REAL NOT NULL,
                total REAL NOT NULL,
                FOREIGN KEY(invoice_id) REFERENCES invoices(id),
                FOREIGN KEY(product_id) REFERENCES products(id)
            );
        """)
        // جدول قيود اليومية
        db.execSQL("""
            CREATE TABLE journal_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                account_id INTEGER NOT NULL,
                debit REAL DEFAULT 0,
                credit REAL DEFAULT 0,
                description TEXT,
                FOREIGN KEY(account_id) REFERENCES accounts(id)
            );
        """)
        // جدول المخزون الحالي
        db.execSQL("""
            CREATE TABLE inventory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                product_id INTEGER NOT NULL,
                warehouse_id INTEGER NOT NULL,
                quantity REAL NOT NULL,
                FOREIGN KEY(product_id) REFERENCES products(id),
                FOREIGN KEY(warehouse_id) REFERENCES warehouses(id)
            );
        """)
        // جدول الإهلاك
        db.execSQL("""
            CREATE TABLE depreciation (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                asset_name TEXT NOT NULL,
                purchase_date TEXT,
                cost REAL,
                useful_life_months INTEGER,
                monthly_depreciation REAL,
                accumulated REAL DEFAULT 0
            );
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // في الإصدارات المستقبلية يمكن تعديل الجداول هنا
    }
}
