interface IntProvider {
  @Provides fun provideInt(stringValue: String? = null): Int = stringValue?.toInt() ?: 0
}